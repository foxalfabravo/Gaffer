/*
 * Copyright 2017-2021 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.gchq.gaffer.federatedstore;

import com.google.common.collect.Sets;
import org.apache.accumulo.core.client.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.gov.gchq.gaffer.accumulostore.AccumuloProperties;
import uk.gov.gchq.gaffer.accumulostore.AccumuloStore;
import uk.gov.gchq.gaffer.accumulostore.utils.TableUtils;
import uk.gov.gchq.gaffer.cache.CacheServiceLoader;
import uk.gov.gchq.gaffer.cache.exception.CacheOperationException;
import uk.gov.gchq.gaffer.commonutil.JsonUtil;
import uk.gov.gchq.gaffer.commonutil.exception.OverwritingException;
import uk.gov.gchq.gaffer.data.elementdefinition.exception.SchemaException;
import uk.gov.gchq.gaffer.federatedstore.exception.StorageException;
import uk.gov.gchq.gaffer.federatedstore.util.FederatedStoreUtil;
import uk.gov.gchq.gaffer.graph.Graph;
import uk.gov.gchq.gaffer.graph.GraphConfig;
import uk.gov.gchq.gaffer.graph.GraphSerialisable;
import uk.gov.gchq.gaffer.operation.OperationException;
import uk.gov.gchq.gaffer.store.Context;
import uk.gov.gchq.gaffer.store.StoreTrait;
import uk.gov.gchq.gaffer.store.library.GraphLibrary;
import uk.gov.gchq.gaffer.store.operation.GetSchema;
import uk.gov.gchq.gaffer.store.operation.GetTraits;
import uk.gov.gchq.gaffer.store.schema.Schema;
import uk.gov.gchq.gaffer.store.schema.Schema.Builder;
import uk.gov.gchq.gaffer.user.User;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static uk.gov.gchq.gaffer.federatedstore.FederatedStoreConstants.KEY_OPERATION_OPTIONS_GRAPH_IDS;

public class FederatedGraphStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(FederatedGraphStorage.class);
    public static final boolean DEFAULT_DISABLED_BY_DEFAULT = false;
    public static final String ERROR_ADDING_GRAPH_TO_CACHE = "Error adding graph, GraphId is known within the cache, but %s is different. GraphId: %s";
    public static final String USER_IS_ATTEMPTING_TO_OVERWRITE = "User is attempting to overwrite a graph within FederatedStore. GraphId: %s";
    public static final String ACCESS_IS_NULL = "Can not put graph into storage without a FederatedAccess key.";
    public static final String GRAPH_IDS_NOT_VISIBLE = "The following graphIds are not visible or do not exist: %s";
    public static final String UNABLE_TO_MERGE_THE_SCHEMAS_FOR_ALL_OF_YOUR_FEDERATED_GRAPHS = "Unable to merge the schemas for all of your federated graphs: %s. You can limit which graphs to query for using the operation option: %s";
    private Map<FederatedAccess, Set<Graph>> storage = new HashMap<>();
    private FederatedStoreCache federatedStoreCache = new FederatedStoreCache();
    private Boolean isCacheEnabled = false;
    private GraphLibrary graphLibrary;

    protected void startCacheServiceLoader() throws StorageException {
        if (CacheServiceLoader.isEnabled()) {
            isCacheEnabled = true;
            makeAllGraphsFromCache();
        }
    }

    /**
     * places a collections of graphs into storage, protected by the given
     * access.
     *
     * @param graphs the graphs to add to the storage.
     * @param access access required to for the graphs, can't be null
     * @throws StorageException if unable to put arguments into storage
     * @see #put(GraphSerialisable, FederatedAccess)
     */
    public void put(final Collection<GraphSerialisable> graphs, final FederatedAccess access) throws StorageException {
        for (final GraphSerialisable graph : graphs) {
            put(graph, access);
        }
    }

    /**
     * places a graph into storage, protected by the given access.
     * <p> GraphId can't already exist, otherwise {@link
     * OverwritingException} is thrown.
     * <p> Access can't be null otherwise {@link IllegalArgumentException} is
     * thrown
     *
     * @param graph  the graph to add to the storage.
     * @param access access required to for the graph.
     * @throws StorageException if unable to put arguments into storage
     */
    public void put(final GraphSerialisable graph, final FederatedAccess access) throws StorageException {
        if (graph != null) {
            String graphId = graph.getDeserialisedConfig().getGraphId();
            try {
                if (null == access) {
                    throw new IllegalArgumentException(ACCESS_IS_NULL);
                }

                if (null != graphLibrary) {
                    graphLibrary.checkExisting(graphId, graph.getDeserialisedSchema(), graph.getDeserialisedProperties());
                }

                validateExisting(graph);
                final Graph builtGraph = graph.getGraph();
                if (isCacheEnabled()) {
                    addToCache(builtGraph, access);
                }

                Set<Graph> existingGraphs = storage.get(access);
                if (null == existingGraphs) {
                    existingGraphs = Sets.newHashSet(builtGraph);
                    storage.put(access, existingGraphs);
                } else {
                    existingGraphs.add(builtGraph);
                }
            } catch (final Exception e) {
                throw new StorageException("Error adding graph " + graphId + " to storage due to: " + e.getMessage(), e);
            }
        } else {
            throw new StorageException("Graph cannot be null");
        }
    }


    /**
     * Returns all the graphIds that are visible for the given user.
     *
     * @param user to match visibility against.
     * @return visible graphIds.
     */
    public Collection<String> getAllIds(final User user) {
        return getIdsFrom(getUserGraphStream(entry -> entry.getKey().hasReadAccess(user)));
    }

    public Collection<String> getAllIds(final User user, final String adminAuth) {
        return getIdsFrom(getUserGraphStream(entry -> entry.getKey().hasReadAccess(user, adminAuth)));
    }

    @Deprecated
    protected Collection<String> getAllIdsAsAdmin() {
        final Stream<Graph> allGraphsAsStream = storage.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream());

        return getIdsFrom(allGraphsAsStream);
    }

    private Collection<String> getIdsFrom(final Stream<Graph> allStream) {
        final Set<String> rtn = allStream
                .map(Graph::getGraphId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return Collections.unmodifiableSet(rtn);
    }

    /**
     * Returns all graph object that are visible for the given user.
     *
     * @param user to match visibility against.
     * @return visible graphs
     */
    public Collection<Graph> getAll(final User user) {
        final Set<Graph> rtn = getUserGraphStream(entry -> entry.getKey().hasReadAccess(user))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableCollection(rtn);
    }

    /**
     * Removes a graph from storage and returns the success. The given user
     * must
     * have visibility of the graph to be able to remove it.
     *
     * @param graphId the graphId to remove.
     * @param user    to match visibility against.
     * @return if a graph was removed.
     * @see #isValidToView(User, FederatedAccess)
     */
    public boolean remove(final String graphId, final User user) {
        return remove(graphId, entry -> entry.getKey().hasWriteAccess(user));
    }

    @Deprecated
    protected boolean remove(final String graphId) {
        return remove(graphId, entry -> true);
    }

    protected boolean remove(final String graphId, final User user, final String adminAuth) {
        return remove(graphId, entry -> entry.getKey().hasWriteAccess(user, adminAuth));
    }

    private boolean remove(final String graphId, final Predicate<Entry<FederatedAccess, Set<Graph>>> entryPredicateForGraphRemoval) {
        return storage.entrySet().stream()
                .filter(entryPredicateForGraphRemoval)
                .map(entry -> {
                    boolean isRemoved = false;
                    final Set<Graph> graphs = entry.getValue();
                    if (null != graphs) {
                        HashSet<Graph> remove = Sets.newHashSet();
                        for (final Graph graph : graphs) {
                            if (graph.getGraphId().equals(graphId)) {
                                remove.add(graph);
                                deleteFromCache(graphId);
                                isRemoved = true;
                            }
                        }
                        graphs.removeAll(remove);
                    }
                    return isRemoved;
                })
                .collect(Collectors.toSet())
                .contains(true);
    }

    private void deleteFromCache(final String graphId) {
        if (isCacheEnabled()) {
            federatedStoreCache.deleteGraphFromCache(graphId);
        }
    }

    /**
     * returns all graphs objects matching the given graphIds, that is visible
     * to the user.
     *
     * @param user     to match visibility against.
     * @param graphIds the graphIds to get graphs for.
     * @return visible graphs from the given graphIds.
     */
    public Collection<Graph> get(final User user, final List<String> graphIds) {
        if (null == user) {
            return Collections.emptyList();
        }

        validateAllGivenGraphIdsAreVisibleForUser(user, graphIds);
        Stream<Graph> graphs = getStream(user, graphIds);
        if (null != graphIds) {
            graphs = graphs.sorted((g1, g2) -> graphIds.indexOf(g1.getGraphId()) - graphIds.indexOf(g2.getGraphId()));
        }
        final Set<Graph> rtn = graphs.collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableCollection(rtn);
    }

    public Schema getSchema(final GetSchema operation, final Context context) {
        if (null == context || null == context.getUser()) {
            // no user then return an empty schema
            return new Schema();
        }

        if (null == operation) {
            return getSchema((Map<String, String>) null, context);
        }

        final List<String> graphIds = FederatedStoreUtil.getGraphIds(operation.getOptions());
        final Stream<Graph> graphs = getStream(context.getUser(), graphIds);
        final Builder schemaBuilder = new Builder();
        try {
            if (operation.isCompact()) {
                final GetSchema getSchema = new GetSchema.Builder()
                        .compact(true)
                        .build();
                graphs.forEach(g -> {
                    try {
                        schemaBuilder.merge(g.execute(getSchema, context));
                    } catch (final OperationException e) {
                        throw new RuntimeException("Unable to fetch schema from graph " + g.getGraphId(), e);
                    }
                });
            } else {
                graphs.forEach(g -> schemaBuilder.merge(g.getSchema()));
            }
        } catch (final SchemaException e) {
            final List<String> resultGraphIds = getStream(context.getUser(), graphIds).map(Graph::getGraphId).collect(Collectors.toList());
            throw new SchemaException("Unable to merge the schemas for all of your federated graphs: " + resultGraphIds + ". You can limit which graphs to query for using the operation option: " + KEY_OPERATION_OPTIONS_GRAPH_IDS, e);
        }
        return schemaBuilder.build();
    }

    /**
     * @param config  configuration containing optional graphIds
     * @param context the user context to match visibility against.
     * @return merged schema of the visible graphs.
     */
    public Schema getSchema(final Map<String, String> config, final Context context) {
        if (null == context) {
            // no context then return an empty schema
            return new Schema();
        }

        return getSchema(config, context.getUser());
    }

    public Schema getSchema(final Map<String, String> config, final User user) {
        if (null == user) {
            // no user then return an empty schema
            return new Schema();
        }

        final List<String> graphIds = FederatedStoreUtil.getGraphIds(config);
        final Stream<Graph> graphs = getStream(user, graphIds);
        final Builder schemaBuilder = new Builder();
        try {
            graphs.forEach(g -> schemaBuilder.merge(g.getSchema()));
        } catch (final SchemaException e) {
            final List<String> resultGraphIds = getStream(user, graphIds).map(Graph::getGraphId).collect(Collectors.toList());
            throw new SchemaException(String.format(UNABLE_TO_MERGE_THE_SCHEMAS_FOR_ALL_OF_YOUR_FEDERATED_GRAPHS, resultGraphIds, KEY_OPERATION_OPTIONS_GRAPH_IDS), e);
        }
        return schemaBuilder.build();
    }

    /**
     * returns a set of {@link StoreTrait} that are common for all visible graphs.
     * traits1 = [a,b,c]
     * traits2 = [b,c]
     * traits3 = [a,b]
     * return [b]
     *
     * @param op      the GetTraits operation
     * @param context the user context
     * @return the set of {@link StoreTrait} that are common for all visible graphs
     * @deprecated use {@link uk.gov.gchq.gaffer.store.Store#execute(uk.gov.gchq.gaffer.operation.Operation, Context)} with GetTraits Operation.
     */
    @Deprecated
    public Set<StoreTrait> getTraits(final GetTraits op, final Context context) {
        boolean firstPass = true;
        final Set<StoreTrait> traits = new HashSet<>();
        if (null != op) {
            final List<String> graphIds = FederatedStoreUtil.getGraphIds(op.getOptions());
            final Collection<Graph> graphs = get(context.getUser(), graphIds);
            final GetTraits getTraits = op.shallowClone();
            for (final Graph graph : graphs) {
                try {
                    Set<StoreTrait> execute = graph.execute(getTraits, context);
                    if (firstPass) {
                        traits.addAll(execute);
                        firstPass = false;
                    } else {
                        traits.retainAll(execute);
                    }
                } catch (final OperationException e) {
                    throw new RuntimeException("Unable to fetch traits from graph " + graph.getGraphId(), e);
                }
            }
        }

        return traits;
    }

    private void validateAllGivenGraphIdsAreVisibleForUser(final User user, final Collection<String> graphIds) {
        if (null != graphIds) {
            final Collection<String> visibleIds = getAllIds(user);
            if (!visibleIds.containsAll(graphIds)) {
                final Set<String> notVisibleIds = Sets.newHashSet(graphIds);
                notVisibleIds.removeAll(visibleIds);
                throw new IllegalArgumentException(String.format(GRAPH_IDS_NOT_VISIBLE, notVisibleIds));
            }
        }
    }

    private void validateExisting(final GraphSerialisable graph) throws StorageException {
        final String graphId = graph.getDeserialisedConfig().getGraphId();
        for (final Set<Graph> graphs : storage.values()) {
            for (final Graph g : graphs) {
                if (g.getGraphId().equals(graphId)) {
                    throw new OverwritingException((String.format(USER_IS_ATTEMPTING_TO_OVERWRITE, graphId)));
                }
            }
        }
    }

    /**
     * @param user   to match visibility against, if null will default to
     *               false/denied
     *               access
     * @param access access the user must match.
     * @return the boolean access
     */
    private boolean isValidToView(final User user, final FederatedAccess access) {
        return null != access && access.hasReadAccess(user);
    }

    /**
     * @param user     to match visibility against.
     * @param graphIds filter on graphIds
     * @return a stream of graphs for the given graphIds and the user has visibility for.
     * If graphIds is null then only enabled by default graphs are returned that the user can see.
     */
    private Stream<Graph> getStream(final User user, final Collection<String> graphIds) {
        if (null == graphIds) {
            return storage.entrySet()
                    .stream()
                    .filter(entry -> isValidToView(user, entry.getKey()))
                    .filter(entry -> !entry.getKey().isDisabledByDefault())
                    .flatMap(entry -> entry.getValue().stream());
        }

        return storage.entrySet()
                .stream()
                .filter(entry -> isValidToView(user, entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(graph -> graphIds.contains(graph.getGraphId()));
    }

    /**
     * @param readAccessPredicate to filter graphs.
     * @return a stream of graphs the user has visibility for.
     */
    private Stream<Graph> getUserGraphStream(final Predicate<Entry<FederatedAccess, Set<Graph>>> readAccessPredicate) {
        return storage.entrySet()
                .stream()
                .filter(readAccessPredicate)
                .flatMap(entry -> entry.getValue().stream());
    }

    private void addToCache(final Graph newGraph, final FederatedAccess access) {
        final String graphId = newGraph.getGraphId();
        if (federatedStoreCache.contains(graphId)) {
            validateSameAsFromCache(newGraph, graphId);
        } else {
            try {
                federatedStoreCache.addGraphToCache(newGraph, access, false);
            } catch (final OverwritingException e) {
                throw new OverwritingException((String.format("User is attempting to overwrite a graph within the cacheService. GraphId: %s", graphId)));
            } catch (final CacheOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void validateSameAsFromCache(final Graph newGraph, final String graphId) {
        final Graph fromCache = federatedStoreCache.getGraphSerialisableFromCache(graphId).getGraph(graphLibrary);
        if (!newGraph.getStoreProperties().getProperties().equals(fromCache.getStoreProperties().getProperties())) {
            throw new RuntimeException(String.format(ERROR_ADDING_GRAPH_TO_CACHE, GraphConfigEnum.PROPERTIES.toString(), graphId));
        } else {
            if (!JsonUtil.equals(newGraph.getSchema().toJson(false), fromCache.getSchema().toJson(false))) {
                throw new RuntimeException(String.format(ERROR_ADDING_GRAPH_TO_CACHE, GraphConfigEnum.SCHEMA.toString(), graphId));
            } else {
                if (!newGraph.getGraphId().equals(fromCache.getGraphId())) {
                    throw new RuntimeException(String.format(ERROR_ADDING_GRAPH_TO_CACHE, "GraphId", graphId));
                }
            }
        }
    }

    public void setGraphLibrary(final GraphLibrary graphLibrary) {
        this.graphLibrary = graphLibrary;
    }

    /**
     * Enum for the Graph Properties or Schema
     */
    public enum GraphConfigEnum {
        SCHEMA("schema"), PROPERTIES("properties");

        private final String value;

        GraphConfigEnum(final String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }

    }

    private Boolean isCacheEnabled() {
        boolean rtn = false;
        if (isCacheEnabled) {
            if (federatedStoreCache.getCache() == null) {
                throw new RuntimeException("No cache has been set, please initialise the FederatedStore instance");
            }
            rtn = true;
        }
        return rtn;
    }

    private void makeGraphFromCache(final String graphId) throws StorageException {
        final GraphSerialisable graph = federatedStoreCache.getGraphSerialisableFromCache(graphId);
        final FederatedAccess accessFromCache = federatedStoreCache.getAccessFromCache(graphId);
        put(graph, accessFromCache);
    }

    private void makeAllGraphsFromCache() throws StorageException {
        final Set<String> allGraphIds = federatedStoreCache.getAllGraphIds();
        for (final String graphId : allGraphIds) {
            try {
                makeGraphFromCache(graphId);
            } catch (final Exception e) {
                LOGGER.error(String.format("Skipping graphId: %s due to: %s", graphId, e.getMessage()), e);
            }
        }
    }

    protected Map<String, Object> getAllGraphsAndAccess(final User user, final List<String> graphIds) {
        return getAllGraphsAndAccess(graphIds, access -> access != null && access.hasReadAccess(user));
    }

    protected Map<String, Object> getAllGraphsAndAccess(final User user, final List<String> graphIds, final String adminAuth) {
        return getAllGraphsAndAccess(graphIds, access -> access != null && access.hasReadAccess(user, adminAuth));
    }

    @Deprecated
    protected Map<String, Object> getAllGraphAndAccessAsAdmin(final List<String> graphIds) {
        return getAllGraphsAndAccess(graphIds, entry -> true);
    }

    private Map<String, Object> getAllGraphsAndAccess(final List<String> graphIds, final Predicate<FederatedAccess> accessPredicate) {
        return storage.entrySet()
                .stream()
                //filter on FederatedAccess
                .filter(e -> accessPredicate.test(e.getKey()))
                //convert to Map<graphID,FederatedAccess>
                .flatMap(entry -> entry.getValue().stream().collect(Collectors.toMap(Graph::getGraphId, g -> entry.getKey())).entrySet().stream())
                //filter on if graph required?
                .filter(entry -> {
                    final boolean isGraphIdRequested = nonNull(graphIds) && graphIds.contains(entry.getKey());
                    final boolean isAllGraphIdsRequired = isNull(graphIds) || graphIds.isEmpty();
                    return isGraphIdRequested || isAllGraphIdsRequired;
                })
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }


    public boolean changeGraphAccess(final String graphId, final FederatedAccess newFederatedAccess, final User requestingUser) throws StorageException {
        return changeGraphAccess(graphId, newFederatedAccess, access -> access.hasWriteAccess(requestingUser));
    }

    public boolean changeGraphAccess(final String graphId, final FederatedAccess newFederatedAccess, final User requestingUser, final String adminAuth) throws StorageException {
        return changeGraphAccess(graphId, newFederatedAccess, access -> access.hasWriteAccess(requestingUser, adminAuth));
    }

    @Deprecated
    public boolean changeGraphAccessAsAdmin(final String graphId, final FederatedAccess newFederatedAccess) throws StorageException {
        return changeGraphAccess(graphId, newFederatedAccess, access -> true);
    }

    private boolean changeGraphAccess(final String graphId, final FederatedAccess newFederatedAccess, final Predicate<FederatedAccess> accessPredicate) throws StorageException {
        boolean rtn;
        final Graph graphToMove = getGraphToMove(graphId, accessPredicate);

        if (nonNull(graphToMove)) {
            //remove graph to be moved
            FederatedAccess oldAccess = null;
            for (final Entry<FederatedAccess, Set<Graph>> entry : storage.entrySet()) {
                entry.getValue().removeIf(graph -> graph.getGraphId().equals(graphId));
                oldAccess = entry.getKey();
            }

            //add the graph being moved.
            this.put(new GraphSerialisable.Builder().graph(graphToMove).build(), newFederatedAccess);

            if (isCacheEnabled()) {
                //Update cache
                try {
                    federatedStoreCache.addGraphToCache(graphToMove, newFederatedAccess, true/*true because graphLibrary should have throw error*/);
                } catch (final CacheOperationException e) {
                    //TODO FS recovery
                    String s = "Error occurred updating graphAccess. GraphStorage=updated, Cache=outdated. graphId:" + graphId;
                    LOGGER.error(s + " graphStorage access:{} cache access:{}", newFederatedAccess, oldAccess);
                    throw new StorageException(s, e);
                }
            }

            rtn = true;
        } else {
            rtn = false;
        }
        return rtn;
    }

    public boolean changeGraphId(final String graphId, final String newGraphId, final User requestingUser) throws StorageException {
        return changeGraphId(graphId, newGraphId, access -> access.hasWriteAccess(requestingUser));
    }

    public boolean changeGraphId(final String graphId, final String newGraphId, final User requestingUser, final String adminAuth) throws StorageException {
        return changeGraphId(graphId, newGraphId, access -> access.hasWriteAccess(requestingUser, adminAuth));
    }

    private boolean changeGraphId(final String graphId, final String newGraphId, final Predicate<FederatedAccess> accessPredicate) throws StorageException {
        boolean rtn;
        final Graph graphToMove = getGraphToMove(graphId, accessPredicate);

        if (nonNull(graphToMove)) {
            FederatedAccess key = null;
            //remove graph to be moved from storage
            for (final Entry<FederatedAccess, Set<Graph>> entry : storage.entrySet()) {
                final boolean removed = entry.getValue().removeIf(graph -> graph.getGraphId().equals(graphId));
                if (removed) {
                    key = entry.getKey();
                    break;
                }
            }

            //Update Tables
            String storeClass = graphToMove.getStoreProperties().getStoreClass();
            if (nonNull(storeClass) && storeClass.startsWith(AccumuloStore.class.getPackage().getName())) {
                /*
                 * This logic is only for Accumulo derived stores Only.
                 * For updating table names to match graphs names.
                 *
                 * uk.gov.gchq.gaffer.accumulostore.[AccumuloStore, SingleUseAccumuloStore,
                 * SingleUseMockAccumuloStore, MockAccumuloStore, MiniAccumuloStore]
                 */
                try {
                    AccumuloProperties tmpAccumuloProps = (AccumuloProperties) graphToMove.getStoreProperties();
                    Connector connection = TableUtils.getConnector(tmpAccumuloProps.getInstance(),
                            tmpAccumuloProps.getZookeepers(),
                            tmpAccumuloProps.getUser(),
                            tmpAccumuloProps.getPassword());

                    if (connection.tableOperations().exists(graphId)) {
                        connection.tableOperations().offline(graphId);
                        connection.tableOperations().rename(graphId, newGraphId);
                        connection.tableOperations().online(newGraphId);
                    }
                } catch (final Exception e) {
                    LOGGER.warn("Error trying to update tables for graphID:{} graphToMove:{}", graphId, graphToMove);
                    LOGGER.warn("Error trying to update tables.", e);
                }
            }

            final GraphConfig configWithNewGraphId = cloneGraphConfigWithNewGraphId(newGraphId, graphToMove);

            //add the graph being renamed.
            GraphSerialisable newGraphSerialisable = new GraphSerialisable.Builder()
                    .graph(graphToMove)
                    .config(configWithNewGraphId)
                    .build();
            this.put(newGraphSerialisable, key);

            //Update cache
            if (isCacheEnabled()) {
                try {
                    //Overwrite cache = true because the graphLibrary should have thrown an error before this point.
                    federatedStoreCache.addGraphToCache(newGraphSerialisable, key, true);
                } catch (final CacheOperationException e) {
                    String s = "Contact Admin for recovery. Error occurred updating graphId. GraphStorage=updated, Cache=outdated graphId.";
                    LOGGER.error(s + " graphStorage graphId:{} cache graphId:{}", newGraphId, graphId);
                    throw new StorageException(s, e);
                }
                federatedStoreCache.deleteGraphFromCache(graphId);
            }

            rtn = true;
        } else {
            rtn = false;
        }
        return rtn;
    }

    private GraphConfig cloneGraphConfigWithNewGraphId(final String newGraphId, final Graph graphToMove) {
        return new GraphConfig.Builder()
                .json(new GraphSerialisable.Builder().graph(graphToMove).build().getConfig())
                .graphId(newGraphId)
                .build();
    }

    private Graph getGraphToMove(final String graphId, final Predicate<FederatedAccess> accessPredicate) {
        Graph graphToMove = null;
        for (final Entry<FederatedAccess, Set<Graph>> entry : storage.entrySet()) {
            if (accessPredicate.test(entry.getKey())) {
                //select graph to be moved
                for (final Graph graph : entry.getValue()) {
                    if (graph.getGraphId().equals(graphId)) {
                        if (isNull(graphToMove)) {
                            //1st match, store graph and continue.
                            graphToMove = graph;
                        } else {
                            //2nd match.
                            throw new IllegalStateException("graphIds are unique, but more than one graph was found with the same graphId: " + graphId);
                        }
                    }
                }
            }
        }
        return graphToMove;
    }


}
