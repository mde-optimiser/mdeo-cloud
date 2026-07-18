package com.mdeo.modeltransformation.ast.patterns

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIntLiteralExpression
import com.mdeo.expression.ast.expressions.TypedBooleanLiteralExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for pattern element data structures and their serialization.
 */
class PatternElementsTest {
    
    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    }
    
    // ========== TypedPatternVariable Tests ==========
    
    @Test
    fun `deserialize TypedPatternVariable`() {
        val jsonString = """{"name": "x", "type": 0, "value": {"kind": "intLiteral", "evalType": 0, "value": "42"}}"""
        val result = json.decodeFromString(TypedPatternVariable.serializer(), jsonString)
        
        assertEquals("x", result.name)
        assertEquals(0, result.type)
        assertIs<TypedIntLiteralExpression>(result.value)
    }
    
    @Test
    fun `deserialize TypedPatternVariable with empty name`() {
        val jsonString = """{"name": "", "value": {"kind": "intLiteral", "evalType": 0, "value": "0"}}"""
        val result = json.decodeFromString(TypedPatternVariable.serializer(), jsonString)
        
        assertEquals("", result.name)
        assertNull(result.type)
    }
    
    @Test
    fun `deserialize TypedPatternVariable with large type index`() {
        val jsonString = """{"name": "var", "type": 99999, "value": {"kind": "intLiteral", "evalType": 0, "value": "1"}}"""
        val result = json.decodeFromString(TypedPatternVariable.serializer(), jsonString)
        
        assertEquals(99999, result.type)
    }
    
    @Test
    fun `deserialize TypedPatternVariable with special characters in name`() {
        val jsonString = """{"name": "my_variable_123", "type": 5, "value": {"kind": "intLiteral", "evalType": 0, "value": "2"}}"""
        val result = json.decodeFromString(TypedPatternVariable.serializer(), jsonString)
        
        assertEquals("my_variable_123", result.name)
    }
    
    @Test
    fun `deserialize TypedPatternVariable with unicode name`() {
        val jsonString = """{"name": "变量名", "type": 1, "value": {"kind": "intLiteral", "evalType": 0, "value": "3"}}"""
        val result = json.decodeFromString(TypedPatternVariable.serializer(), jsonString)
        
        assertEquals("变量名", result.name)
    }
    
    @Test
    fun `deserialize TypedPatternVariable without type annotation`() {
        val jsonString = """{"name": "inferred", "value": {"kind": "booleanLiteral", "evalType": 0, "value": true}}"""
        val result = json.decodeFromString(TypedPatternVariable.serializer(), jsonString)
        
        assertEquals("inferred", result.name)
        assertNull(result.type)
        assertIs<TypedBooleanLiteralExpression>(result.value)
    }
    
    // ========== TypedPatternPropertyAssignment Tests ==========
    
    @Test
    fun `deserialize TypedPatternPropertyAssignment with assignment operator`() {
        val jsonString = """{
            "propertyName": "name",
            "operator": "=",
            "value": {"kind": "intLiteral", "evalType": 0, "value": "42"}
        }"""
        val result = json.decodeFromString(TypedPatternPropertyAssignment.serializer(), jsonString)
        
        assertEquals("name", result.propertyName)
        assertEquals("=", result.operator)
        assertIs<TypedIntLiteralExpression>(result.value)
    }
    
    @Test
    fun `deserialize TypedPatternPropertyAssignment with comparison operator`() {
        val jsonString = """{
            "propertyName": "age",
            "operator": "==",
            "value": {"kind": "intLiteral", "evalType": 0, "value": "25"}
        }"""
        val result = json.decodeFromString(TypedPatternPropertyAssignment.serializer(), jsonString)
        
        assertEquals("age", result.propertyName)
        assertEquals("==", result.operator)
    }
    
    @Test
    fun `deserialize TypedPatternPropertyAssignment with boolean value`() {
        val jsonString = """{
            "propertyName": "isActive",
            "operator": "=",
            "value": {"kind": "booleanLiteral", "evalType": 1, "value": true}
        }"""
        val result = json.decodeFromString(TypedPatternPropertyAssignment.serializer(), jsonString)
        
        assertEquals("isActive", result.propertyName)
        assertIs<TypedBooleanLiteralExpression>(result.value)
        assertEquals(true, result.value.value)
    }
    
    @Test
    fun `deserialize TypedPatternPropertyAssignment with empty property name`() {
        val jsonString = """{
            "propertyName": "",
            "operator": "=",
            "value": {"kind": "intLiteral", "evalType": 0, "value": "0"}
        }"""
        val result = json.decodeFromString(TypedPatternPropertyAssignment.serializer(), jsonString)
        
        assertEquals("", result.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternPropertyAssignment with custom operator`() {
        val jsonString = """{
            "propertyName": "value",
            "operator": "+=",
            "value": {"kind": "intLiteral", "evalType": 0, "value": "10"}
        }"""
        val result = json.decodeFromString(TypedPatternPropertyAssignment.serializer(), jsonString)
        
        assertEquals("+=", result.operator)
    }
    
    // ========== TypedPatternObjectInstance Tests ==========
    
    @Test
    fun `deserialize TypedPatternObjectInstance without modifier`() {
        val jsonString = """{
            "name": "person",
            "className": "com.example.Person",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertNull(result.modifier)
        assertEquals("person", result.name)
        assertEquals("com.example.Person", result.className)
        assertEquals(0, result.properties.size)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with null modifier`() {
        val jsonString = """{
            "modifier": null,
            "name": "obj",
            "className": "MyClass",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertNull(result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with create modifier`() {
        val jsonString = """{
            "modifier": "create",
            "name": "newPerson",
            "className": "com.example.Person",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals("create", result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with delete modifier`() {
        val jsonString = """{
            "modifier": "delete",
            "name": "oldPerson",
            "className": "com.example.Person",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals("delete", result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with forbid modifier`() {
        val jsonString = """{
            "modifier": "forbid",
            "name": "forbidden",
            "className": "com.example.Person",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals("forbid", result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with properties`() {
        val jsonString = """{
            "name": "person",
            "className": "com.example.Person",
            "properties": [
                {
                    "propertyName": "name",
                    "operator": "=",
                    "value": {"kind": "intLiteral", "evalType": 0, "value": "1"}
                },
                {
                    "propertyName": "age",
                    "operator": "==",
                    "value": {"kind": "intLiteral", "evalType": 0, "value": "25"}
                }
            ]
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals(2, result.properties.size)
        assertEquals("name", result.properties[0].propertyName)
        assertEquals("age", result.properties[1].propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with many properties`() {
        val jsonString = """{
            "name": "obj",
            "className": "BigClass",
            "properties": [
                {"propertyName": "p1", "operator": "=", "value": {"kind": "intLiteral", "evalType": 0, "value": "1"}},
                {"propertyName": "p2", "operator": "=", "value": {"kind": "intLiteral", "evalType": 0, "value": "2"}},
                {"propertyName": "p3", "operator": "=", "value": {"kind": "intLiteral", "evalType": 0, "value": "3"}},
                {"propertyName": "p4", "operator": "=", "value": {"kind": "intLiteral", "evalType": 0, "value": "4"}},
                {"propertyName": "p5", "operator": "=", "value": {"kind": "intLiteral", "evalType": 0, "value": "5"}}
            ]
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals(5, result.properties.size)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with empty name`() {
        val jsonString = """{
            "name": "",
            "className": "MyClass",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals("", result.name)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstance with simple class name`() {
        val jsonString = """{
            "name": "obj",
            "className": "Person",
            "properties": []
        }"""
        val result = json.decodeFromString(TypedPatternObjectInstance.serializer(), jsonString)
        
        assertEquals("Person", result.className)
    }
    
    // ========== TypedPatternLinkEnd Tests ==========
    
    @Test
    fun `deserialize TypedPatternLinkEnd without property name`() {
        val jsonString = """{"objectName": "person"}"""
        val result = json.decodeFromString(TypedPatternLinkEnd.serializer(), jsonString)
        
        assertEquals("person", result.objectName)
        assertNull(result.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternLinkEnd with null property name`() {
        val jsonString = """{"objectName": "person", "propertyName": null}"""
        val result = json.decodeFromString(TypedPatternLinkEnd.serializer(), jsonString)
        
        assertNull(result.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternLinkEnd with property name`() {
        val jsonString = """{"objectName": "person", "propertyName": "spouse"}"""
        val result = json.decodeFromString(TypedPatternLinkEnd.serializer(), jsonString)
        
        assertEquals("person", result.objectName)
        assertEquals("spouse", result.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternLinkEnd with empty object name`() {
        val jsonString = """{"objectName": "", "propertyName": "prop"}"""
        val result = json.decodeFromString(TypedPatternLinkEnd.serializer(), jsonString)
        
        assertEquals("", result.objectName)
    }
    
    @Test
    fun `deserialize TypedPatternLinkEnd with empty property name`() {
        val jsonString = """{"objectName": "obj", "propertyName": ""}"""
        val result = json.decodeFromString(TypedPatternLinkEnd.serializer(), jsonString)
        
        assertEquals("", result.propertyName)
    }
    
    // ========== TypedPatternLink Tests ==========
    
    @Test
    fun `deserialize TypedPatternLink without modifier`() {
        val jsonString = """{
            "isOutgoing": true,
            "source": {"objectName": "person1"},
            "target": {"objectName": "person2"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertNull(result.modifier)
        assertEquals("person1", result.source.objectName)
        assertEquals("person2", result.target.objectName)
    }
    
    @Test
    fun `deserialize TypedPatternLink with null modifier`() {
        val jsonString = """{
            "modifier": null,
            "isOutgoing": true,
            "source": {"objectName": "a"},
            "target": {"objectName": "b"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertNull(result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternLink with create modifier`() {
        val jsonString = """{
            "modifier": "create",
            "isOutgoing": true,
            "source": {"objectName": "person1"},
            "target": {"objectName": "person2"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertEquals("create", result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternLink with delete modifier`() {
        val jsonString = """{
            "modifier": "delete",
            "isOutgoing": true,
            "source": {"objectName": "person1"},
            "target": {"objectName": "person2"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertEquals("delete", result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternLink with forbid modifier`() {
        val jsonString = """{
            "modifier": "forbid",
            "isOutgoing": true,
            "source": {"objectName": "person1"},
            "target": {"objectName": "person2"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertEquals("forbid", result.modifier)
    }
    
    @Test
    fun `deserialize TypedPatternLink with property names`() {
        val jsonString = """{
            "isOutgoing": true,
            "source": {"objectName": "person", "propertyName": "spouse"},
            "target": {"objectName": "partner", "propertyName": "spouse"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertEquals("spouse", result.source.propertyName)
        assertEquals("spouse", result.target.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternLink with source property only`() {
        val jsonString = """{
            "isOutgoing": true,
            "source": {"objectName": "parent", "propertyName": "children"},
            "target": {"objectName": "child"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertEquals("children", result.source.propertyName)
        assertNull(result.target.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternLink with target property only`() {
        val jsonString = """{
            "isOutgoing": true,
            "source": {"objectName": "child"},
            "target": {"objectName": "parent", "propertyName": "children"}
        }"""
        val result = json.decodeFromString(TypedPatternLink.serializer(), jsonString)
        
        assertNull(result.source.propertyName)
        assertEquals("children", result.target.propertyName)
    }
    
    // ========== TypedWhereClause Tests ==========
    
    @Test
    fun `deserialize TypedWhereClause with boolean literal`() {
        val jsonString = """{
            "expression": {"kind": "booleanLiteral", "evalType": 0, "value": true}
        }"""
        val result = json.decodeFromString(TypedWhereClause.serializer(), jsonString)
        
        assertIs<TypedBooleanLiteralExpression>(result.expression)
        assertEquals(true, result.expression.value)
    }
    
    @Test
    fun `deserialize TypedWhereClause with false boolean literal`() {
        val jsonString = """{
            "expression": {"kind": "booleanLiteral", "evalType": 0, "value": false}
        }"""
        val result = json.decodeFromString(TypedWhereClause.serializer(), jsonString)
        
        assertIs<TypedBooleanLiteralExpression>(result.expression)
        assertEquals(false, result.expression.value)
    }
    
    @Test
    fun `deserialize TypedWhereClause with integer expression`() {
        val jsonString = """{
            "expression": {"kind": "intLiteral", "evalType": 1, "value": "100"}
        }"""
        val result = json.decodeFromString(TypedWhereClause.serializer(), jsonString)
        
        assertIs<TypedIntLiteralExpression>(result.expression)
        assertEquals("100", result.expression.value)
    }
    
    // ========== Pattern Element Polymorphic Tests ==========
    
    @Test
    fun `deserialize TypedPatternVariableElement`() {
        val jsonString = """{
            "kind": "variable",
            "variable": {"name": "x", "type": 0, "value": {"kind": "intLiteral", "evalType": 0, "value": "42"}}
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)
        
        assertIs<TypedPatternVariableElement>(result)
        assertEquals("variable", result.kind)
        assertEquals("x", result.variable.name)
        assertEquals(0, result.variable.type)
        assertIs<TypedIntLiteralExpression>(result.variable.value)
    }
    
    @Test
    fun `deserialize TypedPatternVariableReassignmentElement`() {
        val jsonString = """{
            "kind": "variableReassignment",
            "reassignment": {"name": "counter", "value": {"kind": "intLiteral", "evalType": 0, "value": "7"}}
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)

        assertIs<TypedPatternVariableReassignmentElement>(result)
        assertEquals("variableReassignment", result.kind)
        assertEquals("counter", result.reassignment.name)
        assertIs<TypedIntLiteralExpression>(result.reassignment.value)
    }

    @Test
    fun `deserialize TypedPatternObjectInstanceElement`() {
        val jsonString = """{
            "kind": "objectInstance",
            "objectInstance": {
                "name": "person",
                "className": "Person",
                "properties": []
            }
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)
        
        assertIs<TypedPatternObjectInstanceElement>(result)
        assertEquals("objectInstance", result.kind)
        assertEquals("person", result.objectInstance.name)
    }
    
    @Test
    fun `deserialize TypedPatternObjectInstanceElement with modifier and properties`() {
        val jsonString = """{
            "kind": "objectInstance",
            "objectInstance": {
                "modifier": "create",
                "name": "newObj",
                "className": "MyClass",
                "properties": [
                    {"propertyName": "prop", "operator": "=", "value": {"kind": "intLiteral", "evalType": 0, "value": "42"}}
                ]
            }
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)
        
        assertIs<TypedPatternObjectInstanceElement>(result)
        assertEquals("create", result.objectInstance.modifier)
        assertEquals(1, result.objectInstance.properties.size)
    }
    
    @Test
    fun `deserialize TypedPatternLinkElement`() {
        val jsonString = """{
            "kind": "link",
            "link": {
                "isOutgoing": true,
                "source": {"objectName": "a"},
                "target": {"objectName": "b"}
            }
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)
        
        assertIs<TypedPatternLinkElement>(result)
        assertEquals("link", result.kind)
        assertEquals("a", result.link.source.objectName)
        assertEquals("b", result.link.target.objectName)
    }
    
    @Test
    fun `deserialize TypedPatternLinkElement with full details`() {
        val jsonString = """{
            "kind": "link",
            "link": {
                "modifier": "delete",
                "isOutgoing": true,
                "source": {"objectName": "parent", "propertyName": "children"},
                "target": {"objectName": "child", "propertyName": "parent"}
            }
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)
        
        assertIs<TypedPatternLinkElement>(result)
        assertEquals("delete", result.link.modifier)
        assertEquals("children", result.link.source.propertyName)
        assertEquals("parent", result.link.target.propertyName)
    }
    
    @Test
    fun `deserialize TypedPatternWhereClauseElement`() {
        val jsonString = """{
            "kind": "whereClause",
            "whereClause": {
                "expression": {"kind": "booleanLiteral", "evalType": 0, "value": true}
            }
        }"""
        val result = json.decodeFromString(TypedPatternElementSerializer, jsonString)
        
        assertIs<TypedPatternWhereClauseElement>(result)
        assertEquals("whereClause", result.kind)
        assertIs<TypedBooleanLiteralExpression>(result.whereClause.expression)
    }
    
    @Test
    fun `deserialize pattern element with unknown kind throws exception`() {
        val jsonString = """{"kind": "unknownKind", "data": {}}"""
        
        assertThrows<IllegalArgumentException> {
            json.decodeFromString(TypedPatternElementSerializer, jsonString)
        }
    }
    
    @Test
    fun `deserialize pattern element without kind throws exception`() {
        val jsonString = """{"variable": {"name": "x", "type": 0}}"""
        
        assertThrows<IllegalArgumentException> {
            json.decodeFromString(TypedPatternElementSerializer, jsonString)
        }
    }
}
