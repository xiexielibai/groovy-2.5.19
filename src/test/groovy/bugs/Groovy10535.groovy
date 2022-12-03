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

import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.Test

final class Groovy10535 {
    @Test
    void testBooleanTypecast_invokeDynamic() {
        def config = new CompilerConfiguration(optimizationOptions: [indy: true])
        new GroovyShell(config).evaluate '''
            @groovy.transform.CompileStatic
            class C {
                static main(args) {
                    Collection<String> strings = null
                    for (int i = 0; i <= 999; i += 1)
                        assert test(strings) == null

                    strings = ['']
                    assert test(strings) != null
                }
                static test(Collection<String> values) {
                    if (values) return 'thing'
                }
            }
        '''
        new GroovyShell(config).evaluate '''
            @groovy.transform.CompileStatic
            class D {
                static main(args) {
                    Collection<String> strings = ['']
                    for (int i = 0; i <= 999; i += 1)
                        assert test(strings) != null

                    strings = null
                    assert test(strings) == null
                }
                static test(Collection<String> values) {
                    if (values) return 'thing'
                }
            }
        '''
    }
}
