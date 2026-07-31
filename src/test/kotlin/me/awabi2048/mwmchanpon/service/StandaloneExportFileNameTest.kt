package me.awabi2048.mwmchanpon.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StandaloneExportFileNameTest {
    @Test
    fun `単一ワールド名を仕様どおり生成する`() {
        assertEquals(
            "［さばちゃんぽん2026］【展示場】.zip",
            StandaloneExportFileName.create(2026, listOf("展示場")),
        )
    }

    @Test
    fun `複数ワールドでは先頭を代表名にする`() {
        assertEquals(
            "［さばちゃんぽん2026］【展示場】ほか.zip",
            StandaloneExportFileName.create(2026, listOf("展示場", "別館")),
        )
    }

    @Test
    fun `ファイル名に使用できないワールド名を拒否する`() {
        listOf("a/b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\nb").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                StandaloneExportFileName.create(2026, listOf(it))
            }
        }
    }
}
