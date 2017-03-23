/*
 * Copyright 2016-2017 Crown Copyright
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

package uk.gov.gchq.gaffer.hazelcast.cache;


import org.junit.*;
import org.junit.rules.ExpectedException;
import uk.gov.gchq.gaffer.cache.ICache;
import uk.gov.gchq.gaffer.cache.exception.CacheOperationException;
import uk.gov.gchq.gaffer.cache.util.CacheSystemProperty;

import java.io.File;

public class HazelCastServiceTest {

    private HazelcastCacheService service = new HazelcastCacheService();
    private static final String CACHE_NAME = "test";

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void before() {
        System.clearProperty(CacheSystemProperty.CACHE_CONFIG_FILE);
    }


    @Test
    public void shouldThrowAnExceptionWhenConfigFileIsMisConfigured() {
        String madeUpFile = "/made/up/file.xml";
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage(madeUpFile);
        System.setProperty(CacheSystemProperty.CACHE_CONFIG_FILE, "/made/up/file.xml");
        service.initialise();
    }

    private void initialiseWithTestConfig() {
        String filePath = new File("src/test/resources/hazelcast.xml").getAbsolutePath();
        System.setProperty(CacheSystemProperty.CACHE_CONFIG_FILE, filePath);
        service.initialise();
    }

    @Test
    public void shouldAllowUserToConfigureCacheUsingConfigFilePath() {

        // given
        initialiseWithTestConfig();

        // when
        ICache<String, Integer> cache = service.getCache(CACHE_NAME);

        // then
        Assert.assertEquals(0, cache.size());
        service.shutdown();
    }

    @Test
    public void shouldReUseCacheIfOneExists() throws CacheOperationException {

        // given
        initialiseWithTestConfig();
        ICache<String, Integer> cache = service.getCache(CACHE_NAME);
        cache.put("key", 1);

        // when
        ICache<String, Integer> sameCache = service.getCache(CACHE_NAME);

        // then
        Assert.assertEquals(1, sameCache.size());
        Assert.assertEquals(new Integer(1), sameCache.get("key"));

        service.shutdown();

    }

    @Test
    public void shouldShareCachesBetweenServices() throws CacheOperationException {

        // given
        initialiseWithTestConfig();
        HazelcastCacheService service1 = new HazelcastCacheService();
        service1.initialise();

        // when
        service1.getCache(CACHE_NAME).put("Test", 2);

        // then
        Assert.assertEquals(1, service.getCache(CACHE_NAME).size());
        Assert.assertEquals(2, service.getCache(CACHE_NAME).get("Test"));

        service1.shutdown();
        service.shutdown();

    }
}
