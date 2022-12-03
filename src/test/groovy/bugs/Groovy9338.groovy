/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package groovy.bugs

import groovy.transform.CompileStatic
import org.junit.Test

import static groovy.test.GroovyAssert.shouldFail

@CompileStatic
final class Groovy9338 {

    @Test
    void testGenericsUnsatisfied1() {
        def err = shouldFail '''
            void meth(Class<? extends CharSequence> c) {
                print c.simpleName
            }
            @groovy.transform.CompileStatic
            void test() {
                def c = (Class<?>) String.class\n
                meth(c)
            }
            test()
        '''
        assert err =~ /Cannot call \w+#meth\(java.lang.Class <\S+ extends java.lang.CharSequence>\) with arguments \[java.lang.Class <\?>\]/
    }

    @Test
    void testGenericsUnsatisfied2() {
        def err = shouldFail '''
            void meth(Class<? super CharSequence> c) {
                print c.simpleName
            }
            @groovy.transform.CompileStatic
            void test() {
                def c = (Class<?>) String.class\n
                meth(c)
            }
            test()
        '''
        assert err =~ /Cannot call \w+#meth\(java.lang.Class <\S+ super java.lang.CharSequence>\) with arguments \[java.lang.Class <\?>\]/
    }
}
