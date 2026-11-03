package com.mdeo.modeltransformation.ast

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.expression.ast.types.LambdaType
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedIfMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedStopStatement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for deserializing the root TypedAst and related types.
 */
class TypedAstTest {
    
    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    }
    
    // ========== Basic TypedAst Deserialization ==========
    
    @Test
    fun `deserialize TypedAst with all fields`() {
        val jsonString = """{
            "types": [
                {"type": "builtin.int", "isNullable": false},
                {"type": "builtin.string", "isNullable": false}
            ],
            "metamodelPath": "file:///path/to/metamodel.mm",
            "statements": [
                {
                    "kind": "match",
                    "pattern": {"elements": []}
                }
            ]
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(2, result.types.size)
        assertEquals("file:///path/to/metamodel.mm", result.metamodelPath)
        assertEquals(1, result.statements.size)
        assertIs<TypedMatchStatement>(result.statements[0])
    }
    
    @Test
    fun `deserialize TypedAst with empty types`() {
        val jsonString = """{
            "types": [],
            "metamodelPath": "file:///metamodel.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(0, result.types.size)
        assertEquals(0, result.statements.size)
    }
    
    @Test
    fun `deserialize TypedAst with empty statements`() {
        val jsonString = """{
            "types": [{"type": "builtin.int", "isNullable": false}],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.types.size)
        assertEquals(0, result.statements.size)
    }
    
    @Test
    fun `deserialize TypedAst with empty metamodelPath`() {
        val jsonString = """{
            "types": [],
            "metamodelPath": "",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals("", result.metamodelPath)
    }
    
    // ========== TypedAst with Different Type Variants ==========
    
    @Test
    fun `deserialize TypedAst with class type`() {
        val jsonString = """{
            "types": [
                {"type": "com.example.Person", "isNullable": false}
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.types.size)
        assertIs<ClassTypeRef>(result.types[0])
        assertEquals("com.example.Person", (result.types[0] as ClassTypeRef).type)
        assertEquals(false, (result.types[0] as ClassTypeRef).isNullable)
    }
    
    @Test
    fun `deserialize TypedAst with nullable class type`() {
        val jsonString = """{
            "types": [
                {"type": "com.example.Person", "isNullable": true}
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.types.size)
        assertIs<ClassTypeRef>(result.types[0])
        assertEquals(true, (result.types[0] as ClassTypeRef).isNullable)
    }
    
    @Test
    fun `deserialize TypedAst with void type`() {
        val jsonString = """{
            "types": [
                {"kind": "void"}
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.types.size)
        assertIs<VoidType>(result.types[0])
    }
    
    @Test
    fun `deserialize TypedAst with lambda type`() {
        val jsonString = """{
            "types": [
                {
                    "returnType": {"type": "builtin.int", "isNullable": false},
                    "parameters": [
                        {"name": "x", "type": {"type": "builtin.int", "isNullable": false}}
                    ],
                    "isNullable": false
                }
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.types.size)
        assertIs<LambdaType>(result.types[0])
    }
    
    @Test
    fun `deserialize TypedAst with multiple types`() {
        val jsonString = """{
            "types": [
                {"type": "builtin.int", "isNullable": false},
                {"type": "builtin.string", "isNullable": true},
                {"type": "builtin.boolean", "isNullable": false},
                {"type": "builtin.double", "isNullable": false},
                {"kind": "void"}
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(5, result.types.size)
    }
    
    // ========== TypedAst with Multiple Statements ==========
    
    @Test
    fun `deserialize TypedAst with multiple statements`() {
        val jsonString = """{
            "types": [{"type": "builtin.int", "isNullable": false}],
            "metamodelPath": "file:///test.mm",
            "statements": [
                {"kind": "match", "pattern": {"elements": []}},
                {"kind": "stop", "keyword": "stop"},
                {"kind": "match", "pattern": {"elements": []}}
            ]
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(3, result.statements.size)
        assertIs<TypedMatchStatement>(result.statements[0])
        assertIs<TypedStopStatement>(result.statements[1])
        assertIs<TypedMatchStatement>(result.statements[2])
    }
    
    @Test
    fun `deserialize TypedAst with nested statements`() {
        val jsonString = """{
            "types": [{"type": "builtin.boolean", "isNullable": false}],
            "metamodelPath": "file:///test.mm",
            "statements": [
                {
                    "kind": "ifMatch",
                    "pattern": {"elements": []},
                    "thenBlock": [
                        {"kind": "stop", "keyword": "stop"}
                    ],
                    "elseBlock": [
                        {"kind": "stop", "keyword": "kill"}
                    ]
                }
            ]
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.statements.size)
        val ifMatch = result.statements[0]
        assertIs<TypedIfMatchStatement>(ifMatch)
        assertEquals(1, ifMatch.thenBlock.size)
        assertEquals(1, ifMatch.elseBlock?.size)
    }
    
    // ========== Edge Cases ==========
    
    @Test
    fun `deserialize TypedAst with special characters in metamodelPath`() {
        val jsonString = """{
            "types": [],
            "metamodelPath": "file:///path/with spaces/and-dashes/test_underscore.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals("file:///path/with spaces/and-dashes/test_underscore.mm", result.metamodelPath)
    }
    
    @Test
    fun `deserialize TypedAst with unicode in metamodelPath`() {
        val jsonString = """{
            "types": [],
            "metamodelPath": "file:///путь/到/ファイル.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals("file:///путь/到/ファイル.mm", result.metamodelPath)
    }
    
    @Test
    fun `deserialize TypedAst with large type index`() {
        val jsonString = """{
            "types": [
                {"type": "type0", "isNullable": false},
                {"type": "type1", "isNullable": false},
                {"type": "type2", "isNullable": false},
                {"type": "type3", "isNullable": false},
                {"type": "type4", "isNullable": false},
                {"type": "type5", "isNullable": false},
                {"type": "type6", "isNullable": false},
                {"type": "type7", "isNullable": false},
                {"type": "type8", "isNullable": false},
                {"type": "type9", "isNullable": false}
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(10, result.types.size)
        for (i in 0..9) {
            assertEquals("type$i", (result.types[i] as ClassTypeRef).type)
        }
    }
    
    @Test
    fun `deserialize TypedAst with http metamodelPath`() {
        val jsonString = """{
            "types": [],
            "metamodelPath": "https://example.com/metamodels/test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals("https://example.com/metamodels/test.mm", result.metamodelPath)
    }
    
    @Test
    fun `deserialize TypedAst with very long type name`() {
        val longTypeName = "com.very.long.package.name.that.keeps.going.and.going.SomeVeryLongClassName"
        val jsonString = """{
            "types": [
                {"type": "$longTypeName", "isNullable": false}
            ],
            "metamodelPath": "file:///test.mm",
            "statements": []
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(1, result.types.size)
        assertEquals(longTypeName, (result.types[0] as ClassTypeRef).type)
    }
    
    // ========== Combined Complex Scenarios ==========
    
    @Test
    fun `deserialize complete TypedAst with all statement types`() {
        val jsonString = """{
            "types": [
                {"type": "builtin.boolean", "isNullable": false},
                {"type": "com.example.Person", "isNullable": false}
            ],
            "metamodelPath": "file:///complex/metamodel.mm",
            "statements": [
                {"kind": "match", "pattern": {"elements": []}},
                {
                    "kind": "ifMatch",
                    "pattern": {"elements": []},
                    "thenBlock": [],
                    "elseBlock": null
                },
                {
                    "kind": "whileMatch",
                    "pattern": {"elements": []},
                    "doBlock": []
                },
                {
                    "kind": "untilMatch",
                    "pattern": {"elements": []},
                    "doBlock": []
                },
                {
                    "kind": "forMatch",
                    "pattern": {"elements": []},
                    "doBlock": []
                },
                {
                    "kind": "ifExpression",
                    "condition": {"kind": "booleanLiteral", "evalType": 0, "value": true},
                    "thenBlock": [],
                    "elseIfBranches": [],
                    "elseBlock": null
                },
                {
                    "kind": "whileExpression",
                    "condition": {"kind": "booleanLiteral", "evalType": 0, "value": false},
                    "block": []
                },
                {"kind": "stop", "keyword": "stop"}
            ]
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)
        
        assertEquals(2, result.types.size)
        assertEquals(8, result.statements.size)
    }

    // ========== Header declarations and model inputs ==========

    @Test
    fun `deserialize model inputs and the model of a scoped object`() {
        val jsonString = """{
            "types": [{"package": "builtin", "type": "model", "isNullable": false}],
            "metamodelPath": "/mm/arch.mm",
            "signature": {
                "name": "take-hosting.mt",
                "parameters": [
                    {"name": "donor", "direction": "in", "type": 0, "isModel": true},
                    {"name": "donors", "direction": "in", "type": 0, "collection": "List", "isModel": true},
                    {"name": "limit", "direction": "in", "type": 0}
                ]
            },
            "statements": [
                {
                    "kind": "match",
                    "pattern": {"elements": [
                        {"kind": "objectInstance", "objectInstance": {"name": "dc", "className": "Component", "properties": [], "model": "donor"}},
                        {"kind": "objectInstance", "objectInstance": {"name": "c", "className": "Component", "properties": []}}
                    ]}
                }
            ]
        }"""
        val result = json.decodeFromString(TypedAst.serializer(), jsonString)

        val signature = result.signature!!
        assertEquals("take-hosting.mt", signature.name)
        assertEquals(listOf("donor", "donors"), signature.modelInputs.map { it.name })
        assertEquals(false, signature.parameters[2].isModel)
        val pattern = (result.statements[0] as TypedMatchStatement).pattern.elements
            .map { (it as com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement).objectInstance }
        assertEquals(listOf("donor", null), pattern.map { it.model })
    }
}
