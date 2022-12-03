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

import groovy.transform.NotYetImplemented

/**
 * Unit tests for static type checking : bug fixes.
 */
class BugsSTCTest extends StaticTypeCheckingTestCase {

    // GROOVY-5456
    void testShouldNotAllowDivOnUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { it / 2 } }
        ''', 'Cannot find matching method java.lang.Object#div(int)'
    }
    void testShouldNotAllowDivBynUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { 2 / it } }
        ''', 'Cannot find matching method int#div(java.lang.Object)'
    }
    void testShouldNotAllowModOnUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { it % 2 } }
        ''', 'Cannot find matching method java.lang.Object#mod(int)'
    }
    void testShouldNotAllowModBynUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { 2 % it } }
        ''', 'Cannot find matching method int#mod(java.lang.Object)'
    }
    void testShouldNotAllowMulOnUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { it * 2 } }
        ''', 'Cannot find matching method java.lang.Object#multiply(int)'
    }
    void testShouldNotAllowMulBynUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { 2 * it } }
        ''', 'Cannot find matching method int#multiply(java.lang.Object)'
    }
    void testShouldNotAllowPlusOnUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { it + 2 } }
        ''', 'Cannot find matching method java.lang.Object#plus(int)'
    }
    void testShouldNotAllowPlusWithUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { 2 + it } }
        ''', 'Cannot find matching method int#plus(java.lang.Object)'
    }
    void testShouldNotAllowMinusOnUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { it - 2 } }
        ''', 'Cannot find matching method java.lang.Object#minus(int)'
    }
    void testShouldNotAllowMinusByUntypedVariable() {
        shouldFailWithMessages '''
            def foo(Closure cls) {}
            def bar() { foo { 2 - it } }
        ''', 'Cannot find matching method int#minus(java.lang.Object)'
    }

    // GROOVY-7929
    void testShouldDetectInvalidMethodUseWithinTraitWithCompileStaticAndSelfType() {
        shouldFailWithMessages '''
            class C {
                def m() { }
            }
            @groovy.transform.CompileStatic
            @groovy.transform.SelfType(C)
            trait T {
                void test() {
                    x()
                }
            }
        ''', 'Cannot find matching method <UnionType:C+T>#x'
    }

    @NotYetImplemented // GROOVY-10102
    void testShouldDetectValidMethodUseWithinTraitWithCompileStaticAndSelfType() {
        assertScript '''
            import groovy.transform.*

            trait A {
                String foo = 'foo'
                String m(String s, Closure x) {
                    s + x()
                }
            }
            @SelfType(A)
            trait B {
            }
            @SelfType(B)
            trait C {
            }
            @CompileStatic
            @SelfType(C)
            trait D {
                def test() {
                    String s = foo
                    m(s) {
                        s.toUpperCase()
                    }
                }
            }

            class E implements A, B, C, D { }
            assert new E().test() == 'fooFOO'
        '''
    }

    // GROOVY-10106
    void testCallStaticOrPrivateMethodInTraitFieldInitializer() {
        ['private', 'static', 'private static'].each { mods ->
            assertScript """
                class C {
                    String s
                }
                trait T {
                    final C c = new C().tap {
                        config(it)
                    }
                    $mods void config(C c) {
                        c.s = 'x'
                    }
                }
                class U implements T {
                }
                def c = new U().c
                assert c.s == 'x'
            """
        }

        shouldFailWithMessages '''
            trait T {
                def obj = new Object().tap {
                    config(it)
                }
                static void config(String s) {
                }
            }
        ''',
        'Cannot find matching method T$Trait$Helper#config(java.lang.Class, java.lang.Object)'
    }

    void testGroovy5444() {
        assertScript '''
            def millis = { System.currentTimeMillis() }

            5.times {
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    assert node.getNodeMetaData(INFERRED_TYPE) == Long_TYPE
                })
                def t0 = millis()
                1000.times {
                    "echo"
                }
                def elapsed = millis() - t0
            }
        '''
    }

    void testGroovy5487ReturnNull() {
        assertScript '''
        @ASTTest(phase=INSTRUCTION_SELECTION, value={
            assert node.getNodeMetaData(INFERRED_RETURN_TYPE) == null // null since 2.1.9
        })
        List getList() {
            null
        }
        '''
    }

    void testGroovy5487ReturnNullWithExplicitReturn() {
        assertScript '''
        @ASTTest(phase=INSTRUCTION_SELECTION, value={
            assert node.getNodeMetaData(INFERRED_RETURN_TYPE) == null // null since 2.1.9
        })
        List getList() {
            return null
        }
        '''
    }

    void testGroovy5487ReturnNullWithEmptyBody() {
        assertScript '''
        @ASTTest(phase=INSTRUCTION_SELECTION, value={
            assert node.getNodeMetaData(INFERRED_RETURN_TYPE) == null // null since 2.1.9
        })
        List getList() {
        }
        '''
    }

    void testGroovy7477NullGenericsType() {
        assertScript '''
            class L<E> extends ArrayList<E> {
                boolean removeIf(Comparator<? super E> filter) {
                }
            }
            def items = ['foo', 'bar'] as L<String>
            items.removeIf({a, b -> 1} as Comparator<String>)
            assert items
        '''

        shouldFailWithMessages '''
            class L<E> extends ArrayList<E> {
                boolean removeIf(Comparator<? super E> filter) {
                }
            }
            L<String> items = ['foo', 'bar'] as L<String>
            items.removeIf({a, b -> 1} as Comparator<?>)
            assert items
        ''',
        'Cannot call L <String>#removeIf(java.util.Comparator <? super java.lang.String>) with arguments [java.util.Comparator <?>]'
    }

    void testGroovy5482ListsAndFlowTyping() {
        assertScript '''
        class StaticGroovy2 {
            def bar() {

                def foo = [new Date(), 1, new C()]
                foo.add( 2 ) // Compiles
                foo.add( new Date() )
                foo.add( new C() )

                foo = [new Date(), 1]
                foo.add( 2 ) // Does not compile
            }
        }
        class C{
        }
        new StaticGroovy2()'''
    }

    void testClosureThisObjectDelegateOwnerProperty() {
        assertScript '''
            class C {
                void m() {
                    C that = this;

                    { ->
                        @ASTTest(phase=INSTRUCTION_SELECTION, value={
                            assert node.getNodeMetaData(INFERRED_TYPE)?.name == 'C'
                        })
                        def ref = thisObject
                        assert ref == that
                    }();

                    { ->
                        @ASTTest(phase=INSTRUCTION_SELECTION, value={
                            assert node.getNodeMetaData(INFERRED_TYPE)?.name == 'C'
                        })
                        def ref = delegate
                        assert ref == that
                    }();

                    { ->
                        @ASTTest(phase=INSTRUCTION_SELECTION, value={
                            assert node.getNodeMetaData(INFERRED_TYPE)?.name == 'C'
                        })
                        def ref = owner
                        assert ref == that
                    }();
                }
            }
            new C().m()
        '''
    }

    void testClosureThisObjectDelegateOwnerAccessor() {
        assertScript '''
            class C {
                void m() {
                    C that = this;

                    { ->
                        @ASTTest(phase=INSTRUCTION_SELECTION, value={
                            assert node.getNodeMetaData(INFERRED_TYPE)?.name == 'C'
                        })
                        def ref = getThisObject()
                        assert ref == that
                    }();

                    { ->
                        @ASTTest(phase=INSTRUCTION_SELECTION, value={
                            assert node.getNodeMetaData(INFERRED_TYPE)?.name == 'C'
                        })
                        def ref = getDelegate()
                        assert ref == that
                    }();

                    { ->
                        @ASTTest(phase=INSTRUCTION_SELECTION, value={
                            assert node.getNodeMetaData(INFERRED_TYPE)?.name == 'C'
                        })
                        def ref = getOwner()
                        assert ref == that
                    }();
                }
            }
            new C().m()
        '''
    }

    // GROOVY-9604
    void testClosureResolveStrategy() {
        assertScript '''
            class C {
                def m() {
                    return { ->
                        resolveStrategy + getResolveStrategy()
                    }();
                }
            }
            assert new C().m() == 0
        '''
    }

    // GROOVY-5616
    void testAssignToGroovyObject() {
        assertScript '''
        class A {}
        GroovyObject obj = new A()
        '''
    }

    void testAssignJavaClassToGroovyObject() {
        shouldFailWithMessages '''
        GroovyObject obj = 'foo'
        ''', 'Cannot assign value of type java.lang.String to variable of type groovy.lang.GroovyObject'
    }

    void testCastToGroovyObject() {
        assertScript '''
        class A {}
        GroovyObject obj = new A()
        '''
    }

    void testAssignInnerClassToGroovyObject() {
        assertScript '''
        class A { static class B {} }
        GroovyObject obj = new A.B()
        '''
    }

    void testCastInnerClassToGroovyObject() {
        assertScript '''
        class A { static class B {} }
        GroovyObject obj = (GroovyObject)new A.B()
        '''
    }

    void testGroovyObjectInGenerics() {
        assertScript '''
        class A {}
        List<? extends GroovyObject> list = new LinkedList<? extends GroovyObject>()
        list.add(new A())
        '''
    }

    // GROOVY-5656
    void testShouldNotThrowAmbiguousMethodError() {
        assertScript '''import groovy.transform.*

        class Expr {}
        class VarExpr extends Expr {}

        class ArgList {
            ArgList(Expr e1) {  }
            ArgList(Expr[] es) {  }
        }

        class Bug4 {
            void test() {
                new ArgList(new VarExpr())
            }
        }

        new Bug4().test()
        '''
    }

    // GROOVY-5793
    void testByteAsParameter() {
        assertScript '''
        void testMethod(java.lang.Byte param){
            println(param)
        }

        void execute(){
            testMethod(java.lang.Byte.valueOf("123"))
        }

        execute()'''
    }

    // GROOVY-5874 (pt.1)
    void testClosureSharedVariableInBinExp() {
        shouldFailWithMessages '''
            def sum = 0
            def cl1 = { sum = sum + 1 }
            def cl2 = { sum = new Date() }

        ''', 'A closure shared variable [sum] has been assigned with various types'
    }

    // GROOVY-5870
    void testShouldNotThrowErrorIfTryingToCastToInterface() {
        assertScript '''
            Set tmp = null
            List other = (List) tmp // should not complain because source and target are interfaces
        '''
    }

    // GROOVY-5889
    void testShouldNotGoIntoInfiniteLoop() {
        assertScript '''
        class Enclosing {
            static class FMessage {
                static enum LogLevel { finest, finer, fine, config, info, warning, severe }
                LogLevel logLevel
            }
        }
        new Enclosing()
        '''
    }

    // GROOVY-5959
    void testSwitchCaseShouldNotRemoveBreakStatements() {
        assertScript '''
        int test(Map<String, String> token) {
          switch(token.type) {
            case 'case one':
              1
              break
            case 'case two':
              2
              break
            default:
              3
              break
          }
        }
        assert test([type:'case one']) == 1
        assert test([type:'case two']) == 2
        assert test([type:'default']) == 3
        '''
    }

    void testShouldChooseFindMethodFromList() {
        assertScript '''
        class Mylist implements List<Object> {

            int size() { }
            boolean isEmpty() {}
            boolean contains(final Object o) {}
            Iterator iterator() {[].iterator()}
            Object[] toArray() {}
            Object[] toArray(final Object[] a) {}
            boolean add(final Object e) {}
            boolean remove(final Object o) {}
            boolean containsAll(final Collection<?> c) {}
            boolean addAll(final Collection c) {}
            boolean addAll(final int index, final Collection c) {}
            boolean removeAll(final Collection<?> c) {}
            boolean retainAll(final Collection<?> c) {}
            void clear() {}
            Object get(final int index) {}
            Object set(final int index, final Object element) {}
            void add(final int index, final Object element) {}
            Object remove(final int index) {}
            int indexOf(final Object o) {}
            int lastIndexOf(final Object o) {}
            ListIterator listIterator() {}
            ListIterator listIterator(final int index) {}
            List subList(final int fromIndex, final int toIndex) {}
        }

           def whatthe(Mylist a) {
               a.find { true }
           }
        whatthe(new Mylist())
        '''
    }

    // GROOVY-6050
    void testShouldAllowTypeConversion() {
        assertScript '''
            interface SomeInterface { void sayHello() }
            void foo(Writer writer) {
                if (writer instanceof SomeInterface) {
                    ((SomeInterface)writer).sayHello()
                }
            }
            foo(null)
        '''
    }

    // GROOVY-6099
    void testFlowTypingErrorWithIfElse() {
        assertScript '''
            def o = new Object()
            boolean b = true
            if (b) {
                o = 1
            } else {
                @ASTTest(phase=INSTRUCTION_SELECTION, value={
                    assert node.getNodeMetaData(INFERRED_TYPE) == OBJECT_TYPE
                })
                def o2 = o
                println (o2.toString())
            }
        '''
    }

    // GROOVY-6104
    void testShouldResolveConstantFromInterfaceImplementedInSuperClass() {
        assertScript '''
            interface Foo {
                public static int MY_CONST = 85
            }
            class FooImpl implements Foo {}
            class Bar extends FooImpl {
                void bar() {
                    assert MY_CONST == 85
                }
            }
            new Bar().bar()
        '''
    }

    // GROOVY-6098
    void testUnresolvedPropertyReferencingIsBooleanMethod() {
        assertScript '''
            boolean isFoo() { true }
            assert foo
        '''
    }

    // GROOVY-6119
    void testShouldCallConstructorWithMap() {
        assertScript '''
            class Foo {
                String message
                Foo(Map map) {
                    message = map.msg
                }
            }
            def foo = new Foo(msg: 'bar')
            assert foo.message == 'bar'
        '''
    }

    // GROOVY-6119
    void testShouldCallConstructorWithHashMap() {
        assertScript '''
            class Foo {
                String message
                Foo(HashMap map) {
                    message = map.msg
                }
            }
            def foo = new Foo(msg: 'bar')
            assert foo.message == 'bar'
        '''
    }

    // GROOVY-6162
    void testShouldConsiderThisInStaticContext() {
        assertScript '''
            class Foo {
                static def staticMethod() {
                    @ASTTest(phase=INSTRUCTION_SELECTION,value={
                        def ift = node.rightExpression.getNodeMetaData(INFERRED_TYPE)
                        assert ift == CLASS_Type
                        assert ift.isUsingGenerics()
                        assert ift.genericsTypes[0].type.name == 'Foo'
                    })
                    def foo = this

                    this.classLoader
                }
            }
            assert Foo.staticMethod() instanceof ClassLoader
        '''
    }

    void testListToSet() {
        assertScript '''
            Set foo(List<Map.Entry> set) {
                set.collect { Map.Entry entry -> entry.key }.toSet()
            }
        '''
    }

    void testConstructorNewInstance() {
        assertScript '''import java.lang.reflect.Constructor

class Person {
    String name
    Person(String name) { this.name = name }
}

Constructor<Person> ctor = Person.getConstructor(String)
def p = ctor.newInstance('Bob')
assert p.name == 'Bob'
'''
    }

    void testOuterDotThisNotation() {
        assertScript '''
class Outer {
    int x
    class Inner {
        int foo() { 2*Outer.this.x }
    }
    int bar() {
        new Inner().foo()
    }
}
def o = new Outer(x:123)
assert o.bar() == 2*o.x
'''
    }

    // GROOVY-6965
    void testShouldNotFailWithClassCastExceptionDuringCompilation() {
        assertScript '''
interface Job {
  Runnable getRunnable()
}


class Printer implements Job{

  protected void execute() {
    println "Printing"
  }

  public void acceptsRunnable(Runnable r){
    r.run()
  }

  public Runnable getRunnable(){
     acceptsRunnable(this.&execute) // OK
     return this.&execute           // compile error
  }
}

Printer
'''
    }

    // GROOVY-6970
    void testShouldBeAbleToChooseBetweenTwoEquivalentInterfaceMethods1() {
        assertScript '''
            interface A { void m() }
            interface B { void m() }
            interface C extends A,B {
            }
            class Impl implements C {
                void m() {}
            }

            void test(C c) {
                c.m()
            }
            test(new Impl())
        '''
    }

    void testShouldBeAbleToChooseBetweenTwoEquivalentInterfaceMethods2() {
        assertScript '''
            interface A { void m() }
            interface B { void m() }
            interface C extends A,B {
            }
            class Impl implements C,A,B {
                void m() {}
            }

            void test(C c) {
                c.m()
            }
            test(new Impl())
        '''
    }

    void testShouldBeAbleToChooseBetweenTwoEquivalentInterfaceMethods3() {
        assertScript '''
            interface A { void m() }
            interface B { void m() }
            class C implements A,B {
                void m() {}
            }

            void test(C c) {
                c.m()
            }
            test(new C())
        '''
    }

    // GROOVY-6849
    void testAmbiguousMethodResolution() {
        assertScript '''
            interface ObservableList<E> extends List<E> {
                public boolean addAll(E... elements)
            }
            public <E> ObservableList<E> wrap(List<E> list) { list as ObservableList }
            ObservableList<String> tags = wrap(['groovy','programming'])
            tags.addAll('bug')
        '''
    }

    // GROOVY-7710
    void testAmbiguousMethodResolutionNoArgsOverload() {
        shouldFailWithMessages '''
            Arrays.sort()
        ''', 'Reference to method is ambiguous. Cannot choose between '
    }

    // GROOVY-7711
    void testAmbiguousMethodResolutionNoArgsCovariantOverride() {
        assertScript '''
            class A {}
            class B {
                Object m(Object[] args) {
                    new Object()
                }
            }
            class C extends B {
                A m(Object[] args) {
                    new A()
                }
            }
            C c = new C()
            A a = c.m()
        '''
    }

    // GROOVY-9006
    void testAmbiguousMethodResolutionTimestampComparedToNull() {
        assertScript '''
            import java.sql.Timestamp

            def test(Timestamp timestamp) {
                if (timestamp != null) { // Reference to method is ambiguous
                    return 'not null'
                }
            }
            def result = test(new Timestamp(new Date().getTime()))
            assert result == 'not null'
        '''
    }

    // GROOVY-6911
    void testShouldNotThrowArrayIndexOfOutBoundsException() {
        assertScript '''
            class MyMap<T> extends LinkedHashMap<String, Object> { }

            class C {
                MyMap bar() { new MyMap() }
            }

            Map<String, Object> m = new C().bar()
            List tmp = (List) m.get("some_key_here")
        '''
    }

    // GROOVY-7416
    void testMethodsFromInterfacesOfSuperClassesShouldBeVisible() {
        assertScript '''
            interface SomeInterface {
                void someInterfaceMethod()
            }

            abstract class AbstractSuperClass implements SomeInterface {}

            abstract class AbstractSubClass extends AbstractSuperClass {
                void someMethod() {
                    someInterfaceMethod()
                }
            }

            assert AbstractSubClass.name == 'AbstractSubClass'
        '''
        assertScript '''
            interface SomeInterface { void foo() }
            interface SomeOtherInterface { void bar() }
            interface AnotherInterface extends SomeInterface, SomeOtherInterface {}

            abstract class Parent implements AnotherInterface {}

            abstract class Child extends Parent {
                void baz() { foo(); bar() }
            }

            assert Child.name == 'Child'
        '''
    }

    // GROOVY-7315
    void testNamedArgConstructorSupportWithInnerClassesAndCS() {
        assertScript '''
            import groovy.transform.*
            @ToString
            class X {
                int a
                static X makeX() { new X(a:1) }
                Y makeY() {
                    new Y(b:2)
                }
                @ToString
                private class Y {
                    int b
                    @ToString
                    private class Z {
                        int c
                    }
                    Z makeZ() {
                        new Z(c:3)
                    }
                }
            }
            assert X.makeX().toString() == 'X(1)'
            assert X.makeX().makeY().toString() == 'X$Y(2)'
            assert X.makeX().makeY().makeZ().toString() == 'X$Y$Z(3)'
        '''
    }

    // GROOVY-8255, GROOVY-8382
    void testTargetTypingEmptyCollectionLiterals() {
        assertScript '''
            class Foo {
                List<List<String>> items = [['x']]
                def bar() {
                    List<String> result = []
                    List<String> selections = items.size() ? (items.get(0) ?: []) : items.size() > 1 ? items.get(1) : []
                    for (String selection: selections) {
                        result << selection
                    }
                    result
                }
            }
            assert new Foo().bar() == ['x']
        '''
        assertScript '''
            class Foo {
                def bar() {
                    def items = [x:1]
                    Map<String, Integer> empty = [:]
                    Map<String, Integer> first = items ?: [:]
                    Map<String, Integer> second = first.isEmpty() ? [:] : [y:2]
                    [first, second]
                }
            }
            assert new Foo().bar() == [[x:1], [y:2]]
        '''
        assertScript '''
            import groovy.transform.*
            @ToString(includeFields=true)
            class Foo {
                List<String> propWithGen = ['propWithGen'] ?: []
                List propNoGen = ['propNoGen'] ?: []
                private Map<String, Integer> fieldGen = [fieldGen:42] ?: [:]
                def bar() {
                    this.propNoGen = ['notDecl'] ?: [] // not applicable here
                    List<String> localVar = ['localVar'] ?: []
                    localVar
                }
            }
            def foo = new Foo()
            assert foo.bar() == ['localVar']
            assert foo.toString() == 'Foo([propWithGen], [notDecl], [fieldGen:42])'
        '''
    }

    // GROOVY-8590
    void testNestedMethodCallInferredTypeInReturnStmt() {
        assertScript '''
            class Source {
                Object getValue() { '32' }
            }
            int m(Source src) {
                return Integer.parseInt((String) src.getValue())
            }
            assert m(new Source()) == 32
        '''
    }

    // GROOVY-10741
    void testMethodPointerPropertyReference() {
        assertScript '''
            class C { def foo }
            def pogo = new C(foo:'bar')
            assert pogo.foo == 'bar'

            def proc = pogo.&setFoo
            proc.call('baz')
            assert pogo.foo == 'baz'
        '''
        shouldFailWithMessages '''
            class C { final foo }
            def pogo = new C(foo:'bar')

            def proc = pogo.&setFoo
        ''', 'Cannot find matching method C#setFoo'
    }

    // GROOVY-9463
    void testMethodPointerUnknownReference() {
        shouldFailWithMessages '''
            def ptr = String.&toLowerCaseX
        ''', 'Cannot find matching method java.lang.String#toLowerCaseX.'
    }

    // GROOVY-9938
    void testInnerClassImplementsInterfaceMethod() {
        assertScript '''
            class Main {
                interface I {
                    def m(@DelegatesTo(value=D, strategy=Closure.DELEGATE_FIRST) Closure c)
                }
                static class C implements I {
                    def m(@DelegatesTo(value=D, strategy=Closure.DELEGATE_FIRST) Closure c) {
                        new D().with(c)
                    }
                }
                static class D {
                    def f() {
                        return 'retval'
                    }
                }
                static main(args) {
                    def result = new C().m { f() }
                    assert result == 'retval'
                }
            }
        '''
    }

    void testInnerClassImplementsInterfaceMethodWithTrait() {
        assertScript '''
            class Main {
                interface I {
                    def m(@DelegatesTo(value=D, strategy=Closure.DELEGATE_FIRST) Closure c)
                }
                trait T {
                    def m(@DelegatesTo(value=D, strategy=Closure.DELEGATE_FIRST) Closure c) {
                        new D().with(c)
                    }
                }
                static class C implements T { // generates m(Closure) that delegates to T#m(Closure)
                }
                static class D {
                    def f() {
                        return 'retval'
                    }
                }
                static main(args) {
                    def result = new C().m { f() }
                    assert result == 'retval'
                }
            }
        '''
    }

    void testInnerClassImplementsInterfaceMethodWithDelegate() {
        assertScript '''
            class Main {
                interface I {
                    def m(@DelegatesTo(value=D, strategy=Closure.DELEGATE_FIRST) Closure c)
                }
                static class T implements I {
                    def m(@DelegatesTo(value=D, strategy=Closure.DELEGATE_FIRST) Closure c) {
                        new D().with(c)
                    }
                }
                static class C implements I {
                    @Delegate(parameterAnnotations=true) T t = new T() // generates m(Closure) that delegates to T#m(Closure)
                }
                static class D {
                    def f() {
                        return 'retval'
                    }
                }
                static main(args) {
                    def result = new C().m { f() }
                    assert result == 'retval'
                }
            }
        '''
    }

    // GROOVY-9951
    void testInnerImmutable() {
        assertScript '''
            class Outer {
                @groovy.transform.Immutable
                static class Inner {
                    String proper
                }
            }

            def obj = new Outer.Inner('value')
            assert obj.proper == 'value'
        '''
    }

    // GROOVY-10424
    void testPrivateInnerClassOptimizedBooleanExpr1() {
        assertScript '''
            class Outer {
                private static class Inner {
                    private Inner() {} // triggers creation of Inner$1 in StaticCompilationVisitor$addPrivateBridgeMethods
                }
                void test() {
                    def inner = new Inner()
                    if (inner) { // optimized boolean expression; StackOverflowError
                        assert true
                    }
                }
            }
            new Outer().test()
        '''
    }

    // GROOVY-10424
    void testPrivateInnerClassOptimizedBooleanExpr2() {
        assertScript '''
            class Outer {
                private static class Inner {
                    static class Three {}
                }
                void test() {
                    def inner = new Inner()
                    if (inner) { // optimized boolean expression; StackOverflowError
                        assert true
                    }
                }
            }
            new Outer().test()
        '''
    }
}
