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
package org.codehaus.groovy.runtime;

import groovy.lang.Closure;
import groovy.util.GroovyTestCase;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class StringGroovyMethodsTest extends GroovyTestCase {

    public void testIncrementString() throws Exception {
        String original = "z";
        String answer = StringGroovyMethods.next(original);

        assertEquals("{", answer);
        assertTrue(answer.compareTo(original) > 0);
    }

    public void testDecrementString() throws Exception {
        String original = "a";
        String answer = StringGroovyMethods.previous(original);

        assertEquals("`", answer);
        assertTrue(ScriptBytecodeAdapter.compareLessThan(answer, original));
    }

    public void testToMethods() throws Exception {
        assertEquals(StringGroovyMethods.toInteger("1"), new Integer(1));
        assertEquals(StringGroovyMethods.toLong("1"), new Long(1));
        assertEquals(StringGroovyMethods.toFloat("1"), new Float(1));
        assertEquals(StringGroovyMethods.toDouble("1"), new Double(1));
        assertEquals(StringGroovyMethods.toBigInteger("1"), new BigInteger("1"));
        assertEquals(StringGroovyMethods.toBigDecimal("1"), new BigDecimal("1"));
        assertEquals(StringGroovyMethods.toBoolean("True"), Boolean.TRUE);
        assertEquals(StringGroovyMethods.toBoolean("Y"), Boolean.TRUE);
        assertEquals(StringGroovyMethods.toBoolean(" y "), Boolean.TRUE);
        assertEquals(StringGroovyMethods.toBoolean("1"), Boolean.TRUE);
        assertEquals(StringGroovyMethods.toBoolean("false"), Boolean.FALSE);
        assertEquals(StringGroovyMethods.toBoolean("n"), Boolean.FALSE);
        assertEquals(StringGroovyMethods.toBoolean("0"), Boolean.FALSE);
    }

    public void testIsMethods() throws Exception {
        String intStr = "123";
        String floatStr = "1.23E-1";
        String nonNumberStr = "ONE";

        assertTrue(StringGroovyMethods.isInteger(intStr));
        assertFalse(StringGroovyMethods.isInteger(floatStr));
        assertFalse(StringGroovyMethods.isInteger(nonNumberStr));
        assertTrue(StringGroovyMethods.isLong(intStr));
        assertFalse(StringGroovyMethods.isLong(floatStr));
        assertFalse(StringGroovyMethods.isLong(nonNumberStr));

        assertTrue(StringGroovyMethods.isFloat(intStr));
        assertTrue(StringGroovyMethods.isFloat(floatStr));
        assertFalse(StringGroovyMethods.isFloat(nonNumberStr));
        assertTrue(StringGroovyMethods.isDouble(intStr));
        assertTrue(StringGroovyMethods.isDouble(floatStr));
        assertFalse(StringGroovyMethods.isDouble(nonNumberStr));

        assertTrue(StringGroovyMethods.isBigInteger(intStr));
        assertFalse(StringGroovyMethods.isBigInteger(floatStr));
        assertFalse(StringGroovyMethods.isBigInteger(nonNumberStr));
        assertTrue(StringGroovyMethods.isBigDecimal(intStr));
        assertTrue(StringGroovyMethods.isBigDecimal(floatStr));
        assertFalse(StringGroovyMethods.isBigDecimal(nonNumberStr));
        assertTrue(StringGroovyMethods.isNumber(intStr));
        assertTrue(StringGroovyMethods.isNumber(floatStr));
        assertFalse(StringGroovyMethods.isNumber(nonNumberStr));
    }

    public void testStartsWithAny() {
        assertTrue(StringGroovyMethods.startsWithAny("abcd", "ab", "ef"));
        assertFalse(StringGroovyMethods.startsWithAny("abcd", "ef", "gh"));
    }

    public void testEndsWithAny() {
        assertTrue(StringGroovyMethods.endsWithAny("abcd", "cd", "ef"));
        assertFalse(StringGroovyMethods.endsWithAny("abcd", "ef", "gh"));
    }

    public void testIsBlank() {
        assertTrue(StringGroovyMethods.isBlank(""));
        assertTrue(StringGroovyMethods.isBlank(" "));
        assertTrue(StringGroovyMethods.isBlank("  "));
        assertTrue(StringGroovyMethods.isBlank("\t"));
        assertTrue(StringGroovyMethods.isBlank("\t\t"));
        assertTrue(StringGroovyMethods.isBlank(" \t"));
        assertTrue(StringGroovyMethods.isBlank("\t "));
        assertTrue(StringGroovyMethods.isBlank(" \n "));
        assertTrue(StringGroovyMethods.isBlank("\n"));
        assertTrue(StringGroovyMethods.isBlank("\n\n"));
        assertTrue(StringGroovyMethods.isBlank(" \n"));
        assertTrue(StringGroovyMethods.isBlank("\n "));
        assertTrue(StringGroovyMethods.isBlank(" \n "));
        assertTrue(StringGroovyMethods.isBlank(" \n \t "));
        assertFalse(StringGroovyMethods.isBlank("abc"));
        assertFalse(StringGroovyMethods.isBlank("abc "));
        assertFalse(StringGroovyMethods.isBlank(" abc"));
        assertFalse(StringGroovyMethods.isBlank(" abc "));
        assertFalse(StringGroovyMethods.isBlank("\tabc"));
        assertFalse(StringGroovyMethods.isBlank("abc\t"));
        assertFalse(StringGroovyMethods.isBlank("\tabc\t"));
    }

    public void testFindAllFromCharSequenceWithClosure() {
        CharSequence charSequence = new StringBuilder().append("ABCD");
        String regex = "(A)(B)(C)(D)";
        Closure<String> closure = createClosureForFindOrFindAll();
        List<String> result = StringGroovyMethods.findAll(charSequence, regex, closure);
        List<String> expectedResult = Collections.singletonList("B");
        assertEquals(expectedResult, result);
    }

    public void testFindAllFromCharSequenceWithPatternAndClosure() {
        CharSequence charSequence = new StringBuilder().append("ABCD");
        Pattern pattern = Pattern.compile("(A)(B)(C)(D)");
        Closure<String> closure = createClosureForFindOrFindAll();
        List<String> result = StringGroovyMethods.findAll(charSequence, pattern, closure);
        List<String> expectedResult = Collections.singletonList("B");
        assertEquals(expectedResult, result);
    }

    public void testFindAllFromStringWithClosure() {
        String string = "ABCD";
        String regex = "(A)(B)(C)(D)";
        Closure<String> closure = createClosureForFindOrFindAll();
        List<String> result = StringGroovyMethods.findAll(string, regex, closure);
        List<String> expectedResult = Collections.singletonList("B");
        assertEquals(expectedResult, result);
    }

    public void testFindAllFromStringWithPatternAndClosure() {
        String string = "ABCD";
        Pattern pattern = Pattern.compile("(A)(B)(C)(D)");
        Closure<String> closure = createClosureForFindOrFindAll();
        List<String> result = StringGroovyMethods.findAll(string, pattern, closure);
        List<String> expectedResult = Collections.singletonList("B");
        assertEquals(expectedResult, result);
    }

    public void testFindFromCharSequenceWithClosure() {
        CharSequence charSequence = new StringBuilder().append("ABCD");
        String regex = "(A)(B)(C)(D)";
        Closure<String> closure = createClosureForFindOrFindAll();
        String result = StringGroovyMethods.find(charSequence, regex, closure);
        String expectedResult = "B";
        assertEquals(expectedResult, result);
    }

    public void testFindFromCharSequenceWithPatternAndClosure() {
        CharSequence charSequence = new StringBuilder().append("ABCD");
        Pattern pattern = Pattern.compile("(A)(B)(C)(D)");
        Closure<String> closure = createClosureForFindOrFindAll();
        String result = StringGroovyMethods.find(charSequence, pattern, closure);
        String expectedResult = "B";
        assertEquals(expectedResult, result);
    }

    public void testFindFromStringWithClosure() {
        String string = "ABCD";
        String regex = "(A)(B)(C)(D)";
        Closure<String> closure = createClosureForFindOrFindAll();
        String result = StringGroovyMethods.find(string, regex, closure);
        String expectedResult = "B";
        assertEquals(expectedResult, result);
    }

    public void testFindFromStringWithPatternAndClosure() {
        String string = "ABCD";
        Pattern pattern = Pattern.compile("(A)(B)(C)(D)");
        Closure<String> closure = createClosureForFindOrFindAll();
        String result = StringGroovyMethods.find(string, pattern, closure);
        String expectedResult = "B";
        assertEquals(expectedResult, result);
    }

    public void testisAtLeast() {
        assertTrue(StringGroovyMethods.isAtLeast("2.1", "2.1"));
        assertTrue(StringGroovyMethods.isAtLeast("2.1", "2.0"));
        assertTrue(StringGroovyMethods.isAtLeast("3.0", "2.1"));
        assertFalse(StringGroovyMethods.isAtLeast("2.5", "3.0"));
    }

    private Closure<String> createClosureForFindOrFindAll() {
        return new Closure<String>(this) {
            @Override
            public String call(Object arguments) {
                assertTrue(arguments instanceof List);
                return ((List) arguments).get(2).toString();
            }

            @Override
            public String call(Object... args) {
                return call((Object) args);
            }
        };
    }

}
