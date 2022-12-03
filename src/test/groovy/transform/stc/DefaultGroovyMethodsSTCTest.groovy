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
 * Unit tests for static type checking : default groovy methods.
 */
class DefaultGroovyMethodsSTCTest extends StaticTypeCheckingTestCase {

    void testEach() {
        assertScript '''
            ['a','b'].each { // DGM#each(Object, Closure)
                println it // DGM#println(Object,Object)
            }
        '''

        assertScript '''
            ['a','b'].eachWithIndex { it, i ->// DGM#eachWithIndex(Object, Closure)
                println it // DGM#println(Object,Object)
            }
        '''
    }

    void testStringToInteger() {
        assertScript '''
            String name = "123"
            name.toInteger() // toInteger() is defined by DGM
        '''
    }

    void testVariousAssignmentsThenToInteger() {
        assertScript '''
            class A {
                void foo() {}
            }
            def name = new A()
            name.foo()
            name = 1
            name = '123'
            name.toInteger() // toInteger() is defined by DGM
        '''
    }

    void testMethodsOnPrimitiveTypes() {
        assertScript '''
            1.times { it }
        '''

        assertScript '''
            true.equals { it }
        '''
    }

    void testShouldAcceptMethodFromDefaultDateMethods() {
      assertScript '''
        def s = new Date()
        println s.year
        println s.format("yyyyMMdd")
      '''
    }

    // GROOVY-5568
    void testPropertySemantics1() {
        assertScript '''
            String test(InputStream input) {
                input.text // IOGroovyMethods#getText(InputStream)
            }
            assert test(new ByteArrayInputStream('foo'.bytes)) == 'foo'
        '''

        assertScript '''
            def chars = new StringBuilder('foo').chars // StringGroovyMethods#getChars(CharSequence)
            assert chars == 'foo'.toCharArray()
        '''

        assertScript '''
            def a = Character.valueOf((char) 'a')
            assert a.letter // DefaultGroovyMethods#isLetter(Character)
        '''
    }

    // GROOVY-10075
    void testPropertySemantics2() {
        // see org.codehaus.groovy.runtime.m12n.TestStringExtension

        assertScript '''
            List<String> strings = ['x','y','z']
            assert strings.getSequence() == 'x'
            assert strings.getString() == 'x'
          //assert strings.sequence == 'x'
          //assert strings.string == 'x'
        '''

        shouldFailWithMessages '''
            List<Number> numbers = [(Number)1, 2, 3]
            numbers.getSequence()
            numbers.getString()
            numbers.sequence
            numbers.string
        ''',
        'Cannot call <CS extends java.lang.CharSequence> java.util.List <java.lang.Number>#getSequence() with arguments []',
        'Cannot call java.util.List <java.lang.Number>#getString() with arguments []',
        'No such property: sequence for class: java.util.List <java.lang.Number>',
        'No such property: string for class: java.util.List <java.lang.Number>'
    }

    // GROOVY-5584
    void testEachOnMap() {
        assertScript '''import org.codehaus.groovy.transform.stc.ExtensionMethodNode
            import org.codehaus.groovy.runtime.DefaultGroovyMethods

            @ASTTest(phase=INSTRUCTION_SELECTION, value= {
                def mn = node.rightExpression.getNodeMetaData(DIRECT_METHOD_CALL_TARGET)
                assert mn
                assert mn instanceof ExtensionMethodNode
                assert mn.declaringClass == MAP_TYPE
                def en = mn.extensionMethodNode
                assert en.declaringClass == make(DefaultGroovyMethods)
                assert en.parameters[0].type == MAP_TYPE
            })
            def x = [a:1, b:3].each { k, v -> "$k$v" }
        '''
    }

    // GROOVY-6961
    void testCollectMany() {
        assertScript '''
            class ListCompilerAndReverser {
                static List<Integer> revlist(List<List<String>> list) {
                    list.collectMany { strings ->
                        strings.collect {
                            it.toInteger()
                        }
                    } sort { int it ->
                        -it
                    }
                }
            }

            assert ListCompilerAndReverser.revlist([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]]) == [9, 8, 7, 6, 5, 4, 3, 2, 1]
        '''
    }

    // GROOVY-7283
    void testArrayMinMaxSupportsOneAndTwoArgClosures() {
        assertScript '''
            Date now = new Date()
            Date then = now + 7
            def dates = [now, then] as Date[]
            assert dates.min() == now
            assert dates.max() == then
            assert dates.min{ d -> d.time } == now
            assert dates.max{ d1, d2 -> d2.time <=> d1.time } == now
        '''
    }

    // GROOVY-7283
    void testListWithDefaultInfersInt() {
        assertScript '''
            def list = [].withDefault{ it.longValue() }
            list[0] = list[3]
            assert list[0] == 3 && list[0].class == Long
        '''
    }

    // GROOVY-7952
    void testIsGetterMethodAsProperty() {
        assertScript '''
            assert !'abc'.allWhitespace
        '''
    }

    // GROOVY-7976
    void testSortMethodsWithComparatorAcceptingSubclass() {
        assertScript '''
            class SecondLetterComparator implements Comparator<? extends CharSequence> {
                int compare(CharSequence cs1, CharSequence cs2) {
                    cs1.charAt(1) <=> cs2.charAt(1)
                }
            }

            def orig1 = ['ant', 'rat', 'bug', 'dog']
            def sorted1 = orig1.sort(false, new SecondLetterComparator())
            assert orig1 == ['ant', 'rat', 'bug', 'dog']
            assert sorted1 == ['rat', 'ant', 'dog', 'bug']

            String[] orig2 = ['ant', 'rat', 'bug', 'dog']
            def sorted2 = orig2.sort(false, new SecondLetterComparator())
            assert orig2 == ['ant', 'rat', 'bug', 'dog']
            assert sorted2 == ['rat', 'ant', 'dog', 'bug']
            orig2.sort(new SecondLetterComparator())
            assert orig2 == ['rat', 'ant', 'dog', 'bug']

            def orig3 = [ant:5, rat:10, bug:15, dog:20]
            def sorted3 = orig3.sort(new SecondLetterComparator())
            assert orig3 == [ant:5, rat:10, bug:15, dog:20]
            assert sorted3*.value == [10, 5, 20, 15]
        '''
    }

    // GROOVY-8205
    void testEachOnEnumValues() {
        assertScript '''
            enum Functions {
                A, B, C
            }
            def m() {
                def results = []
                Functions.values().each { results << it.name() }
                results
            }
            def m2() {
                def results = [:]
                Functions.values().eachWithIndex { val, idx -> results[idx] = val.name() }
                results
            }
            assert m() == ['A', 'B', 'C']
            assert m2() == [0: 'A', 1: 'B', 2: 'C']
        '''
    }

    void testListGetAtNext() {
        assertScript '''
            def test() {
                def list = [0, 1, 2, 3]
                for (i in 1..2) {
                    list[i-1]++
                }
                list
            }
            assert test() == [1, 2, 2, 3]
        '''
    }

    // GROOVY-8840
    void testListGetAtGetAtNext() {
        assertScript '''
            def test() {
                def list = [0, 1, 2, 3]
                List<Integer> other = [1]
                list[other[0]]++
                //   ^^^^^^^^ puts T on operand stack, not int/Integer
                list
            }
            assert test() == [0, 2, 2, 3]
        '''
    }

    void testListGetAtGetAtNext2() {
        assertScript '''
            def test() {
                def list = [0, 1, 2, 3]
                List<Integer> other = [1]
                list[(int)other[0]]++
                list
            }
            assert test() == [0, 2, 2, 3]
        '''
    }

    void testListGetAtFirstNext() {
        assertScript '''
            def test() {
                def list = [0, 1, 2, 3]
                List<Integer> other = [1]
                list[other.first()]++
                list
            }
            assert test() == [0, 2, 2, 3]
        '''
    }
}
