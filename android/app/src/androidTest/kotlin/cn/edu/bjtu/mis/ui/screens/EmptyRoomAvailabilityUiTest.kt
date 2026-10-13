package cn.edu.bjtu.mis.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmptyRoomSlotHeader
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyRoomAvailabilityUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val data = mutableStateOf(sampleData())
    private val visible = mutableStateOf(true)

    @Test
    fun selectingEndAndDayImmediatelyChangesTheAvailableRooms() {
        showResults()
        compose.onNodeWithText("SX101").assertIsDisplayed()
        compose.onNodeWithText("SX102").assertIsDisplayed()
        compose.onNodeWithText("SX103").assertDoesNotExist()

        select("结束节次", "第 2 节")
        compose.onNodeWithText("SX101").assertIsDisplayed()
        compose.onNodeWithText("SX102").assertDoesNotExist()
        compose.onNodeWithText("座位数：60").assertIsDisplayed()
        capture("empty-room-filter.png")

        select("星期 / 日期", "星期二 10月06日")
        compose.onNodeWithText("SX101").assertDoesNotExist()
        compose.onNodeWithText("SX102").assertIsDisplayed()
    }

    @Test
    fun advancingStartKeepsEndValidAndDisplaysNoMatchMessage() {
        showResults()
        select("开始节次", "第 3 节")
        compose.onNodeWithText("星期一 10月05日 · 第 3 节 · 0 间空教室").assertIsDisplayed()
        compose.onNodeWithText("该时段暂无符合条件的空教室").assertIsDisplayed()
    }

    @Test
    fun selectedRangeSurvivesLoadingAndAdaptsToTheNewWeeksDates() {
        showResults()
        select("结束节次", "第 2 节")
        select("星期 / 日期", "星期二 10月06日")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            data.value = sampleData().copy(
                slots = sampleData().slots.map { it.copy(date = if (it.day == "星期一") "10月12日" else "10月13日") },
                rooms = listOf(EmptyRoomRow("SY201", cellStates = listOf("busy", "busy", "busy", "free", "free", "busy"))),
            )
            visible.value = true
        }
        compose.onNodeWithText("星期二 10月13日 · 第 1–2 节 · 1 间空教室").assertIsDisplayed()
        compose.onNodeWithText("SY201").assertIsDisplayed()
    }

    @Test
    fun missingHeadersCannotShowAnUnverifiedFreeRoom() {
        data.value = EmptyRoomData(rooms = listOf(EmptyRoomRow("SX101", availability = listOf(true))))
        showResults()
        compose.onNodeWithText("暂无可筛选的时间段").assertIsDisplayed()
        compose.onNodeWithText("SX101").assertDoesNotExist()
    }

    private fun showResults() {
        compose.setContent {
            MaterialTheme {
                var selection by remember { mutableStateOf(EmptyRoomTimeSelection()) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (visible.value) {
                        EmptyRoomAvailabilityResults(data.value, selection) { selection = it }
                    }
                }
            }
        }
    }

    private fun select(label: String, option: String) {
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText(option).performClick()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun sampleData() = EmptyRoomData(
        slots = listOf("星期一" to "10月05日", "星期二" to "10月06日").flatMap { (day, date) ->
            (1..3).map { EmptyRoomSlotHeader(day, date, it) }
        },
        rooms = listOf(
            EmptyRoomRow("SX101", "60", cellStates = listOf("free", "free", "busy", "busy", "free", "busy")),
            EmptyRoomRow("SX102", "40", cellStates = listOf("free", "busy", "busy", "free", "free", "busy")),
            EmptyRoomRow("SX103", "30", cellStates = listOf("notice", "free", "busy", "busy", "busy", "busy")),
        ),
    )
}
