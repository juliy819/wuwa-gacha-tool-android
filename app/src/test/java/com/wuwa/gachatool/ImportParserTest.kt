package com.wuwa.gachatool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportParserTest {
    @Test fun mapsEveryOfficialApiPoolName() {
        val expected = listOf(
            "角色精准调谐", "武器精准调谐", "角色常驻调谐", "武器常驻调谐", "新手调谐",
            "新手自选调谐", "新手自选调谐（感恩定向调谐）", "角色新旅调谐", "武器新旅调谐",
            "角色联动调谐", "武器联动调谐", "角色忆旅调谐", "武器忆旅调谐",
        )
        expected.forEachIndexed { index, name -> assertEquals((index + 1).toString(), ImportParser.poolIdFromApiName(name)) }
        assertNull(ImportParser.poolIdFromApiName("不存在的卡池"))
    }

    @Test fun hashParametersOverrideTopLevelParametersLikeDesktop() {
        val result = ImportParser.params("https://example.com/?player_id=111111111&record_id=old#/record?player_id=106485288&record_id=new")
        assertEquals("106485288", result.uid)
        assertEquals("new", result.recordId)
    }

    @Test fun rejectsBlankRequiredParametersLikeDesktop() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportParser.params("https://example.com/#/record?player_id=&record_id=")
        }
    }

    @Test fun keepsOptionalParametersEmptyWhenTheyAreAbsent() {
        val result = ImportParser.params("https://example.com/#/record?player_id=106485288&record_id=record")
        assertEquals("", result.resourcesId)
        assertEquals("", result.serverId)
        assertEquals("", result.lang)
    }
}
