package com.yinu.app

import com.yinu.app.records.InformationKind
import com.yinu.app.records.RecordInformation
import com.yinu.app.records.extractRecordInformation
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordInformationTest {
    @Test
    fun mixedFieldsStaySeparateAndLabeledTrackingWinsOverPhoneGuess() {
        val text = "地址：上海市徐汇区漕溪北路88号 电话：138 0013 8000\n运单号：13900139000\n回拨 13800138000"
        assertEquals(
            listOf(
                RecordInformation(InformationKind.Address, "上海市徐汇区漕溪北路88号"),
                RecordInformation(InformationKind.Phone, "138 0013 8000"),
                RecordInformation(InformationKind.Tracking, "13900139000"),
            ),
            extractRecordInformation(text),
        )
    }
}
