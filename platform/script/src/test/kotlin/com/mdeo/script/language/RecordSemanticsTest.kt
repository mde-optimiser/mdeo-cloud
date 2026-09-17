package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Records in less common shapes and uses, end to end from `language/records-more.fn`, which
 * imports records with defaults from `language/records-shapes.fn` saved as `/shapes.fn`.
 */
class RecordSemanticsTest : FrontendAstTest() {

    private val program = compile(mapOf(SCRIPT_PATH to "records-more", "/shapes.fn" to "records-shapes"))

    @Test
    fun `the field defaults of an imported record see its earlier fields`() {
        assertEquals(
            "Box(name=unit, size=Size(width=1.0, height=1.0), area=1.0) 6.0 Size(width=1.0, height=1.0)",
            run(program, "importedDefaults")
        )
    }

    @Test
    fun `a record refers to itself`() {
        assertEquals(
            "6 Node(value=1, next=Node(value=2, next=Node(value=3, next=null)))",
            run(program, "linkedList")
        )
    }

    @Test
    fun `a record without fields equals every other of its record`() {
        assertEquals("Empty()truefalse", run(program, "emptyRecords"))
    }

    @Test
    fun `records of different records with equal fields are not equal`() {
        assertEquals(false, run(program, "sameShapeDifferentRecords"))
    }

    @Test
    fun `collection fields are compared by content, at the time of comparison`() {
        assertEquals("truefalseBucket(items=[1, 2])", run(program, "collectionFieldsCompareByContent"))
    }

    @Test
    fun `fields may have the names of members of the runtime record class`() {
        assertEquals(
            "Odd(copy=11, field=f, fields=2, hashCode=3, recordType=r, values=4) 11f23r40",
            run(program, "fieldsNamedLikeRuntimeMembers")
        )
    }

    @Test
    fun `fields of every primitive, nullable and Any type are written and copied`() {
        assertEquals(
            "Mixed(i=2, f=5.0, l=3000000000000, b=false, s=s, d=4.5, any=x) " +
                "Mixed(i=2, f=5.0, l=3000000000000, b=false, s=null, d=null, any=7) -1.0",
            run(program, "allFieldKinds")
        )
    }

    @Test
    fun `the arguments of with are evaluated in the order they are written`() {
        assertEquals("sanyb sanytrue", run(program, "withEvaluationOrder"))
    }

    @Test
    fun `the receiver of with is evaluated once`() {
        assertEquals("19.0", run(program, "withReceiverEvaluatedOnce"))
    }

    @Test
    fun `with replaces every field`() {
        assertEquals(
            "Box(name=a, size=Size(width=1.0, height=1.0), area=1.0) Box(name=c, size=Size(width=2.0, height=2.0), area=4.0)",
            run(program, "withAllFields")
        )
    }

    @Test
    fun `with copies an imported record and leaves the original unchanged`() {
        assertEquals(true, run(program, "withOnImported"))
    }

    @Test
    fun `collection lambdas map, filter and sort records`() {
        assertEquals("4.0;6.0;3.0true", run(program, "lambdasOverRecords"))
    }

    @Test
    fun `a record in a field is the same record as in a list`() {
        assertEquals("6.0 Holder(points=[Pt(x=1.0), Pt(x=6.0)], best=Pt(x=6.0))", run(program, "nestedMutation"))
    }

    @Test
    fun `a set keeps one of equal records`() {
        assertEquals("2truefalse", run(program, "recordsInSets"))
    }

    @Test
    fun `reassigning a variable does not change the record it held`() {
        assertEquals("1.03.0", run(program, "reassignRecordVariable"))
    }

    @Test
    fun `records are passed and returned by reference`() {
        assertEquals("9.0true", run(program, "recordsAsParameters"))
    }

    @Test
    fun `a default value is evaluated for every construction`() {
        assertEquals(0, run(program, "defaultRecordsAreFresh"))
    }

    @Test
    fun `null-safe access reads nullable record fields`() {
        assertEquals("-1.04.0", run(program, "nullableRecordFields"))
    }

    @Test
    fun `nullable fields are concatenated to strings`() {
        assertEquals("d=2.5 s=null any=null", run(program, "nullableDoubleInString"))
    }
}
