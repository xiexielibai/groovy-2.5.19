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
package groovy.transform.stc

/**
 * Unit tests for static type checking : ternary operator.
 */
class TernaryOperatorSTCTest extends StaticTypeCheckingTestCase {

    void testByteByte() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == byte_TYPE
            })
            def y = true?(byte)1:(byte)0
        '''
    }

    void testShortShort() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == short_TYPE
            })
            def y = true?(short)1:(short)0
        '''
    }

    void testIntInt() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == int_TYPE
            })
            def y = true?1:0
        '''
    }

    void testLongLong() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == long_TYPE
            })
            def y = true?1L:0L
        '''
    }

    void testFloatFloat() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == float_TYPE
            })
            def y = true?1f:0f
        '''
    }

    void testDoubleDouble() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == double_TYPE
            })
            def y = true?1d:0d
        '''
    }

    void testBoolBool() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == boolean_TYPE
            })
            def y = true?true:false
        '''
    }

    void testDoubleFloat() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == double_TYPE
            })
            def y = true?1d:1f
        '''
    }

    void testDoubleDoubleWithBoxedTypes() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == Double_TYPE
            })
            def y = true?new Double(1d):new Double(1f)
        '''
    }

    void testDoubleFloatWithBoxedTypes() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == Double_TYPE
            })
            def y = true?new Double(1d):new Float(1f)
        '''
    }

    void testDoubleFloatWithOneBoxedType1() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == Double_TYPE
            })
            def y = true?1d:new Float(1f)
        '''
    }

    void testDoubleFloatWithOneBoxedType2() {
        assertScript '''
            @ASTTest(phase=INSTRUCTION_SELECTION, value={
                assert node.getNodeMetaData(INFERRED_TYPE) == Double_TYPE
            })
            def y = true?new Double(1d):1f
        '''
    }

    // GROOVY-10330
    void testTypeParameterTypeParameter1() {
        assertScript '''
            import org.apache.groovy.internal.util.Function

            class C<T> {
                T y
                void m(T x, Function<T, T> f) {
                    assert f.apply(x) == 'foo'
                }
                void test(T x, Function<T, T> f) {
                    @ASTTest(phase=INSTRUCTION_SELECTION, value={
                        def type = node.getNodeMetaData(INFERRED_TYPE)
                        assert type.isGenericsPlaceHolder()
                        assert type.unresolvedName == 'T'
                    })
                    def z = true ? x : y
                    m(z, f)
                }
            }
            new C<String>().test('FOO', { it.toLowerCase() })
        '''
    }

    // GROOVY-10363
    void testTypeParameterTypeParameter2() {
        assertScript '''
            def <X extends java.util.concurrent.Callable<Number>> X m(X x, X y) {
                X ecks = true ? x : y // infers as Callable<Object>
            }
            assert m(null,null) == null
        '''
    }

    // GROOVY-10357
    void testAbstractMethodDefault() {
        assertScript '''
            import org.apache.groovy.internal.util.Function

            abstract class A {
                abstract long m(Function<Boolean,Integer> f = { Boolean b -> b ? +1 : -1 })
            }

            def a = new A() {
                @Override
                long m(Function<Boolean,Integer> f) {
                    f.apply(true).longValue()
                }
            }
            assert a.m() == 1L
        '''
    }

    // GROOVY-10358
    void testCommonInterface() {
        assertScript '''
            interface I {
                int m(int i)
            }
            abstract class A implements I {
            }
            class B<T> extends A {
                int m(int i) {
                    i + 1
                }
            }
            class C<T> extends A {
                int m(int i) {
                    i - 1
                }
            }

            C<String> c = null; int i = 1
            int x = (false ? c : new B<String>()).m(i) // Cannot find matching method A#m(int)
            assert x == 2
        '''
    }

    // GROOVY-10603
    void testCommonInterface2() {
        assertScript '''
            interface I {}
            interface J extends I {}
            class Foo implements I {}
            class Bar implements J {}

            I test(Foo x, Bar y) {
                true ? x : y // Cannot return value of type GroovyObject for method returning I
            }
            test(null, null)
        '''
    }

    // GROOVY-5523
    void testNull1() {
        assertScript '''
            def findFile() {
                String str = ""
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    assert node.getNodeMetaData(INFERRED_TYPE) == make(File)
                })
                File f = str ? new File(str) : null
            }
        '''
        assertScript '''
            def findFile() {
                String str = ""
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    assert node.getNodeMetaData(INFERRED_TYPE) == make(File)
                })
                File f = str ? null : new File(str)
            }
        '''
        assertScript '''
            def findFile() {
                String str = ""
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    assert node.getNodeMetaData(INFERRED_TYPE) == make(File)
                })
                File f = str ? null : null
            }
        '''
    }

    void testNull2() {
        assertScript '''
            def test(String str) {
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    assert node.getNodeMetaData(INFERRED_TYPE) == STRING_TYPE
                })
                String s = str ?: null
            }

            assert test('x') == 'x'
            assert test('') == null
        '''
    }

    // GROOVY-5734
    void testNull3() {
        assertScript '''
            Integer test() { false ? null : 42 }

            assert test() == 42
        '''
    }

    // GROOVY-7507
    void testNull4() {
        assertScript '''
            class B {
            }
            class C {
                B b
            }
            boolean check = true
            C c = new C()
            c.b = check ? new B() : null // Cannot assign value of type Object to variable of type Bar
        '''
    }

    // GROOVY-10226
    void testNull5() {
        assertScript '''
            class A<T> {
            }
            def <T extends A<String>> T test() {
                final T x = null
                true ? (T) null : x
            }
            assert test() == null
        '''
    }

    // GROOVY-10158
    void testNull6() {
        assertScript '''
            class A<T> {
            }
            class B<T extends A<String>> {
                T m() {
                    final T x = null
                    final T y = null
                    ( true ? x : y )
                }
            }
            assert new B<A<String>>().m() == null
        '''
    }
}
