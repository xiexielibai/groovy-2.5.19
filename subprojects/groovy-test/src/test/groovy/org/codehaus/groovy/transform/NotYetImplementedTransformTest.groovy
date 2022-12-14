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
package org.codehaus.groovy.transform

import junit.framework.AssertionFailedError

class NotYetImplementedTransformTest extends GroovyShellTestCase {

    void testNotYetImplemented() {
        def output = evaluate("""
              import groovy.transform.NotYetImplemented

              class MyTests extends GroovyTestCase {
                @NotYetImplemented void testShouldNotFail()  {
                    assertFalse true
                }
              }

              junit.textui.TestRunner.run(new junit.framework.TestSuite(MyTests))
        """)

        assertNotNull output
        assertTrue "test method marked with @NotYetImplemented must NOT throw an AsssertionFailedError", output.wasSuccessful()
    }

    void testNotYetImplementedWithException() {
            def output = evaluate("""
                  import groovy.transform.NotYetImplemented

                  class MyTests extends GroovyTestCase {
                    @NotYetImplemented void testShouldNotFail()  {
                        'test'.yetUnkownMethod()
                    }
                  }

                  junit.textui.TestRunner.run(new junit.framework.TestSuite(MyTests))
            """)

            assertNotNull output
            assertTrue "test method marked with @NotYetImplemented must NOT throw an AsssertionFailedError", output.wasSuccessful()
        }

    void testNotYetImplementedPassThrough() {
        def output = evaluate("""
              import groovy.transform.NotYetImplemented

              class MyTests extends GroovyTestCase {
                @NotYetImplemented void testShouldFail()  {
                    assertTrue true
                }
              }

              junit.textui.TestRunner.run(new junit.framework.TestSuite(MyTests))
        """)

        assertNotNull output

        assertEquals "test method marked with @NotYetImplemented must throw an AssertionFailedError", 1, output.failureCount()
        assertEquals "test method marked with @NotYetImplemented must throw an AssertionFailedError", AssertionFailedError, output.failures().nextElement().thrownException().class
    }

    void testEmptyTestMethod() {
        def output = evaluate("""
              import groovy.transform.NotYetImplemented

              class MyTests extends GroovyTestCase {
                @NotYetImplemented void testShouldNotFail()  {}
              }

              junit.textui.TestRunner.run(new junit.framework.TestSuite(MyTests))
        """)

        assertNotNull output

        assertTrue "empty test method must not throw an AssertionFailedError", output.wasSuccessful()
    }

    void testNotYetImplementedJUnit4()  {

        def output = evaluate("""
        import groovy.transform.NotYetImplemented
        import org.junit.Test
        import org.junit.runner.JUnitCore

        class MyTests {
            @NotYetImplemented @Test void testShouldNotFail()  {
                junit.framework.Assert.assertFalse true
            }
        }

        new JUnitCore().run(MyTests)
        """)

        assertTrue output.wasSuccessful()

    }

    void testNotYetImplementedPassThroughJUnit4() {
        def output = evaluate("""
              import groovy.transform.NotYetImplemented
              import org.junit.Test
              import org.junit.runner.JUnitCore

              class MyTests {
                @NotYetImplemented @Test void shouldFail()  {
                    junit.framework.Assert.assertTrue true
                }
              }

              new JUnitCore().run(MyTests)
        """)

        assertNotNull output

        assertEquals "test method marked with @NotYetImplemented must throw an AssertionFailedError", 1, output.failureCount
        assertEquals "test method marked with @NotYetImplemented must throw an AssertionFailedError", AssertionFailedError, output.failures.first().exception.class
    }

    // GROOVY-8457
    void testNotYetImplementedJUnit4Failure_atCompileStatic()  {
        def output = shell.evaluate('''
            import groovy.transform.CompileStatic
            import groovy.transform.NotYetImplemented
            import org.junit.Test
            import org.junit.runner.JUnitCore

            @CompileStatic
            class MyTests {
                @NotYetImplemented @Test void testThatFails()  {
                    assert false
                }
            }

            new JUnitCore().run(MyTests)
        ''')

        assert output.wasSuccessful() : '@CompileStatic @Test method with failing assertion marked with @NotYetImplemented should pass'
    }


    // GROOVY-8457
    void testNotYetImplementedJUnit4Success_atCompileStatic() {
        def output = shell.evaluate('''
            import groovy.transform.CompileStatic
            import groovy.transform.NotYetImplemented
            import org.junit.Test
            import org.junit.runner.JUnitCore

            @CompileStatic
            class MyTests {
                @NotYetImplemented @Test void testThatFails()  {
                    assert true
                }
            }

            new JUnitCore().run(MyTests)
        ''')
        assert output.failureCount == 1 : 'Passing @CompileStatic @Test method marked with @NotYetImplemented should fail'
        assert output.failures.first().exception instanceof AssertionFailedError : 'Passing @CompileStatic @Test method marked with @NotYetImplemented should throw an AssertionFailedError'
    }
}