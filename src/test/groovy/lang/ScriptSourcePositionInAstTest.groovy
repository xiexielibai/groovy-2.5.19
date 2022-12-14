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
package groovy.lang

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.runtime.StringGroovyMethods

/**
 * Check that scripts have proper source position in the AST
 */
class ScriptSourcePositionInAstTest extends GroovyTestCase {

    private positionsForScript(String text) {
        CompilationUnit cu = new CompilationUnit()
        cu.addSource("scriptSourcePosition.groovy", text)
        cu.compile(Phases.SEMANTIC_ANALYSIS)

        def node = cu.getAST().getClass("scriptSourcePosition")

        [[node.getLineNumber(), node.getColumnNumber()], [node.getLastLineNumber(), node.getLastColumnNumber()]]
    }

    void testEmptyScript() {
        assert positionsForScript("") == [[-1, -1], [-1, -1]]
    }

    void testSingleStatementScript() {
        assert positionsForScript("println 'hello'") == [[1, 1], [1, 16]]
    }

    void testDoubleStatementScript() {
        def raw = """\
            println 'hello'
            println 'bye'
        """
        // avoid stripIndent issues on JDK13+ by calling Groovy's DGM stripIndent explicitly
        def script = StringGroovyMethods.stripIndent((CharSequence)raw)
        assert positionsForScript(script) == [[1, 1], [2, 14]]
    }

    void testScriptWithClasses() {
        def raw = """\
            class Bar {}
            println 'hello'
            println 'bye'
            class Baz{}
        """
        // avoid stripIndent issues on JDK13+ by calling Groovy's DGM stripIndent explicitly
        def script = StringGroovyMethods.stripIndent((CharSequence)raw)
        assert positionsForScript(script) == [[2, 1], [3, 14]]
    }
}
