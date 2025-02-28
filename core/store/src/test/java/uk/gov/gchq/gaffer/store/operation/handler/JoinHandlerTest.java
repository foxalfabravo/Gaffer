/*
 * Copyright 2015-2021 Crown Copyright
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

package uk.gov.gchq.gaffer.store.operation.handler;

import org.junit.jupiter.api.Test;

import uk.gov.gchq.gaffer.operation.OperationException;
import uk.gov.gchq.gaffer.operation.impl.join.Join;
import uk.gov.gchq.gaffer.operation.impl.join.match.MatchKey;
import uk.gov.gchq.gaffer.operation.impl.join.methods.JoinType;
import uk.gov.gchq.gaffer.store.Context;
import uk.gov.gchq.gaffer.store.Store;
import uk.gov.gchq.gaffer.store.operation.handler.join.JoinHandler;
import uk.gov.gchq.gaffer.store.operation.handler.join.match.ElementMatch;
import uk.gov.gchq.gaffer.store.operation.handler.join.match.KeyFunctionMatch;
import uk.gov.gchq.gaffer.user.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class JoinHandlerTest {

    private final Store store = mock(Store.class);
    private final Context context = new Context(new User());

    @Test
    public void shouldSetInputToNewArrayListWhenNull() throws OperationException {
        // Given
        final JoinHandler handler = new JoinHandler();

        final Join joinOp = new Join.Builder<>()
                .joinType(JoinType.FULL)
                .matchMethod(new ElementMatch())
                .matchKey(MatchKey.LEFT)
                .build();

        // When
        handler.doOperation(joinOp, context, store);

        // Then
        assertTrue(joinOp.getInput().equals(new ArrayList<>()));
    }

    @Test
    public void shouldThrowExceptionWhenInputIsMoreThanLimit() {
        // Given
        final JoinHandler handler = new JoinHandler();
        final List<Integer> inputList = Arrays.asList(1, 2, 3);

        final Join<Integer> joinOp = new Join.Builder<Integer>()
                .input(inputList)
                .joinType(JoinType.FULL)
                .matchKey(MatchKey.LEFT)
                .matchMethod(new KeyFunctionMatch())
                .collectionLimit(1)
                .build();

        // When / Then
        assertThatExceptionOfType(OperationException.class)
                .isThrownBy(() -> handler.doOperation(joinOp, context, store))
                .withMessageContaining("exceeded");
    }

    @Test
    public void shouldThrowExceptionWhenNoMatchMethodIsSpecified() {
        // Given
        final JoinHandler handler = new JoinHandler();
        final List<Integer> inputList = Arrays.asList(1, 2, 3);

        Join<Object> joinOp = new Join.Builder<>()
                .input(inputList)
                .joinType(JoinType.FULL)
                .matchKey(MatchKey.LEFT)
                .build();

        // When / Then
        assertThatExceptionOfType(OperationException.class).isThrownBy(() -> handler.doOperation(joinOp, context, store)).withMessage("A match method must be supplied");
    }
}
