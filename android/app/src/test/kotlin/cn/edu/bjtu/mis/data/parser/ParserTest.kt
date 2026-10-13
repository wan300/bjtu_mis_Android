package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test
    fun parseTimetableFixture() {
        val data = parseTimetable(text("timetable.html"))
        assertEquals(2, data.entries.size)
        assertEquals("M310005B", data.entries.first().courseCode)
    }

    @Test
    fun parseCourseSelectionFixture() {
        val parsed = parseCourseSelectionPage(text("course_selection.html"))
        assertTrue(parsed.data.canSubmit)
        assertEquals(1, parsed.data.selectedCourses.size)
        assertEquals(2, parsed.data.availableCourses.size)
        assertEquals("M410003B_01", parsed.data.availableCourses.first().key)
        assertEquals(2, parsed.data.availableCourses.first().remaining)
        assertEquals("target-1", parsed.actions.getValue("M410003B_01").fields["selects"])
        assertEquals("selected-1", parsed.dropActions.getValue("M310005B_01").fields["pk"])
        assertTrue(parsed.dropActions.getValue("M310005B_01").actionUrl.endsWith("/course_selection/courseselecttask/delete/"))
        assertEquals(0, parsed.data.availableCourses[1].remaining)
        assertEquals("target-2", parsed.actions.getValue("M410004B_01").fields["selects"])
    }

    @Test
    fun parseCourseSelectionDetectsTableByCourseSignals() {
        val parsed = parseCourseSelectionPage(text("course_selection_detected_table.html"))

        assertTrue(parsed.data.canSubmit)
        assertEquals(1, parsed.data.selectedCourses.size)
        assertEquals(1, parsed.data.availableCourses.size)
        assertEquals("M500001B_02", parsed.data.availableCourses.single().key)
        assertEquals(3, parsed.data.availableCourses.single().remaining)
        assertEquals("late-checkbox", parsed.actions.getValue("M500001B_02").fields["selects"])
        assertEquals("selected-late", parsed.dropActions.getValue("M310005B_01").fields["pk"])
    }

    @Test
    fun parseCourseSelectionSelectsActionTable() {
        val parsed = parseCourseSelectionPage(
            text("course_selection_selects_action.html"),
            "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects_action/?action=load&iframe=school",
        )

        assertTrue(parsed.data.canSubmit)
        assertEquals(1, parsed.data.selectedCourses.size)
        assertEquals("A101001B_01", parsed.data.selectedCourses.single().key)
        assertEquals(2, parsed.data.availableCourses.size)
        assertEquals("A101005B_01", parsed.data.availableCourses.first().key)
        assertEquals(1, parsed.data.availableCourses.first().remaining)
        assertEquals("101005", parsed.actions.getValue("A101005B_01").fields["checkboxs"])
        assertTrue(parsed.actions.getValue("A101005B_01").actionUrl.endsWith("/course_selection/courseselecttask/selects_action/?action=submit"))
        assertEquals(0, parsed.data.availableCourses[1].remaining)
        assertTrue("A101005B_02" !in parsed.actions)
    }

    @Test
    fun parseCourseSelectionSelectedStatusTable() {
        val parsed = parseCourseSelectionPage(
            text("course_selection_selects_action_selected.html"),
            "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects/",
        )

        assertEquals(1, parsed.data.selectedCourses.size)
        assertEquals("A101005B_01", parsed.data.selectedCourses.single().key)
        assertTrue(parsed.data.availableCourses.isEmpty())
    }

    @Test
    fun parseCourseSelectionCaptchaForm() {
        val parsed = parseCourseSelectionCaptcha(
            """
            <div class="modal">
              <form action="/course_selection/courseselecttask/captcha/">
                <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
                <img src="/captcha/image/1.png" />
                <input type="text" name="captcha" />
              </form>
            </div>
            """.trimIndent(),
            "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects/",
        )

        assertEquals("https://aa.bjtu.edu.cn/captcha/image/1.png", parsed.imageUrl)
        assertEquals("captcha", parsed.inputName)
        assertEquals("csrf-token", parsed.fields["csrfmiddlewaretoken"])
        assertTrue(parsed.fields.getValue("__action__").endsWith("/course_selection/courseselecttask/captcha/"))
    }

    @Test
    fun parseCourseSelectionCaptchaBootboxWithoutForm() {
        val parsed = parseCourseSelectionCaptcha(
            """
            <div class="modal bootbox">
              <div class="captcha-dialog">
                <input type="hidden" name="hashkey" value="hash-token" />
                <img src="/captcha/image/hash-token/" />
                <input name="answer" />
              </div>
            </div>
            """.trimIndent(),
            "https://aa.bjtu.edu.cn/course_selection/courseselecttask/selects/",
        )

        assertEquals("https://aa.bjtu.edu.cn/captcha/image/hash-token/", parsed.imageUrl)
        assertEquals("answer", parsed.inputName)
        assertEquals("hash-token", parsed.fields["hashkey"])
        assertTrue(parsed.fields.getValue("__action__").endsWith("/course_selection/courseselecttask/selects/"))
    }

    @Test
    fun parseTeachingAssessmentListFixture() {
        val data = parseTeachingAssessmentList(text("teaching_assessment_list.html"))

        assertEquals("2025-2026-2-2 待评教课程列表", data.title)
        assertEquals(2, data.courses.size)
        assertEquals("1001", data.courses[0].id)
        assertTrue(data.courses[0].canEvaluate)
        assertEquals("/teaching_assessment/stu/1001/update/", data.courses[0].actionPath)
        assertEquals("待评教", data.courses[0].status)
        assertEquals("1002", data.courses[1].id)
        assertEquals(false, data.courses[1].canEvaluate)
        assertEquals("已评教", data.courses[1].status)
    }

    @Test
    fun parseTeachingAssessmentFormFixture() {
        val form = parseTeachingAssessmentForm(
            text("teaching_assessment_form.html"),
            "https://aa.bjtu.edu.cn/teaching_assessment/stu/1001/update/",
        )

        assertEquals("1001", form.courseId)
        assertEquals("post", form.method)
        assertEquals("csrf-token", form.fields["csrfmiddlewaretoken"])
        assertEquals("2", form.fields["select-TOTAL_FORMS"])
        assertEquals(0, form.unsupportedMultiCount)
        assertEquals(2, form.questions.size)
        assertEquals("9001", form.questions[0].resultId)
        assertEquals("1115", form.questions[0].recommendedValue)
        assertEquals("非常符合", form.questions[0].options.first { it.value == "1115" }.label)
        assertEquals("2112", form.questions[1].recommendedValue)
        assertEquals("优秀", form.questions[1].options.first { it.value == "2112" }.label)
        assertEquals(1, form.comments.size)
        assertEquals("comment-0-comment_result", form.comments.single().name)
        assertEquals("9003", form.comments.single().resultId)
    }

    @Test
    fun parseScoresFixture() {
        val data = parseScores(text("scores_main.html"))
        assertEquals(2, data.items.size)
        assertTrue(data.items.first().courseName.isNotBlank())
        assertNull(data.items.first().detail)
        assertEquals("/score/scores/stu/detail/1001/?term=2025-2026-2-2", data.items.first().detailPath)
    }

    @Test
    fun parseScoreDetailFixture() {
        val data = parseScoreDetail(text("score_detail.html"))
        assertEquals("软件项目管理与产品运维 成绩详情", data.title)
        assertEquals("课程", data.fields.first().label)
        assertEquals("软件项目管理与产品运维", data.fields.first().value)
        assertEquals(listOf("项目", "比例", "成绩"), data.tables[0].headers)
        assertEquals(listOf("平时", "40%", "88"), data.tables[0].rows.first())
    }

    @Test
    fun parseScoreDetailLabelValueFixture() {
        val data = parseScoreDetail(text("score_detail_label_value.html"))

        assertEquals("成绩详情", data.title)
        assertEquals("88", data.fields.first { it.label == "平时成绩" }.value)
        val componentTable = data.tables.single { it.title == "分项成绩" }
        assertEquals(listOf("项目", "比例", "成绩"), componentTable.headers)
        assertEquals(listOf("平时", "40%", "88"), componentTable.rows[0])
        assertEquals(listOf("期末", "60%", "85"), componentTable.rows[1])
    }

    @Test
    fun parseEmptyRoomsFixture() {
        val data = parseEmptyRooms(text("empty_rooms.html"), mapOf("week" to "8"))
        assertEquals(4, data.slots.size)
        assertEquals(2, data.rooms.size)
        assertEquals(listOf(true, false, true, true), data.rooms[1].availability)
        assertEquals(listOf("free", "busy", "free", "free"), data.rooms[1].cellStates)
        assertEquals("2024-2025-2-1", data.query["term"])
        assertEquals("8", data.query["week"])
        assertEquals("SX", data.query["building"])
        assertEquals(listOf("2024-2025-1-1", "2024-2025-2-1"), data.availableTerms.map { it.value })
        assertEquals(listOf("7", "8"), data.availableWeeks.map { it.value })
        assertEquals(listOf("", "SX"), data.availableBuildings.map { it.value })
    }

    @Test
    fun parseEmptyRoomsPreservesExplicitlyClearedFilters() {
        val data = parseEmptyRooms(
            text("empty_rooms.html"),
            mapOf("building" to "", "room" to "", "week" to "8"),
        )

        assertEquals("", data.query["building"])
        assertEquals("", data.query["room"])
        assertEquals("8", data.query["week"])
        assertEquals("2024-2025-2-1", data.query["term"])
    }

    @Test
    fun parseEmptyRoomsCellStatesFromStyles() {
        val html = """
            <table class="table table-bordered">
              <tr>
                <th>星期</th>
                <th colspan="3">星期一 05月04日</th>
              </tr>
              <tr>
                <th>教室/节次</th>
                <th>1</th>
                <th>2</th>
                <th>3</th>
              </tr>
              <tr>
                <td>SY101 (90)</td>
                <td style="background-color: #e46868"></td>
                <td style="background-color: #fff"></td>
                <td style="background-color: #ffff00"></td>
              </tr>
            </table>
        """.trimIndent()

        val data = parseEmptyRooms(html, mapOf("week" to "8"))

        assertEquals(listOf(false, true, false), data.rooms.single().availability)
        assertEquals(listOf("busy", "free", "notice"), data.rooms.single().cellStates)
    }

    @Test
    fun parseCalendarAndHomeworkFixtures() {
        val (terms, currentTerm) = parseCalendarTerms(json("calendar_terms.json"))
        val calendar = parseCalendar(json("calendar_month.json"), "2026-04", currentTerm, terms)
        assertEquals("8", calendar.currentWeek)
        assertEquals(3, calendar.items.size)

        val courses = parseCourses(json("course_list.json"))
        val homework = parseHomeworkList(json("homework_open.json"), courses.first(), 0)
        assertEquals(1, homework.size)
        assertEquals("open", homework.first().status)
        assertEquals("M410001B", homework.first().courseCode)
    }

    @Test
    fun parseHomeworkStatusUsesSubmissionTime() {
        val course = parseCourses(json("course_list.json")).first()
        val payload = AppJson.parseToJsonElement(
            """
            {
              "courseNoteList": [
                {"id": 1, "title": "Open homework"},
                {"id": 2, "title": "Submitted homework", "subTime": "2026-04-10 23:05:56", "subStatus": "submitted"},
                {"id": 3, "title": "Closed homework", "can_submit": "0"},
                {"id": 4, "title": "Makeup homework", "subStatus": "可补交"},
                {"id": 5, "title": "Closed by status", "subStatus": "不可补交"}
              ],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject

        val homework = parseHomeworkList(payload, course, 0)

        assertEquals(listOf("open", "done", "open", "open", "open"), homework.map { it.status })
        assertEquals(null, homework[0].submittedAt)
        assertEquals("2026-04-10 23:05:56", homework[1].submittedAt)
        assertEquals(false, homework[2].canSubmit)
        assertEquals(true, homework[2].canSubmitExplicit)
        assertEquals(true, homework[3].canSubmit)
        assertEquals(true, homework[3].canSubmitExplicit)
        assertEquals(false, homework[4].canSubmit)
        assertEquals(true, homework[4].canSubmitExplicit)
    }

    @Test
    fun parseHomeworkKeepsFullRequirementBesideExcerpt() {
        val course = parseCourses(json("course_list.json")).first()
        val longBody = (1..80).joinToString(" ") { "第${it}段作业要求" }
        val payload = AppJson.parseToJsonElement(
            """
            {
              "courseNoteList": [
                {"id": 1, "title": "Long homework", "content": "<p>$longBody</p>"}
              ],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject

        val homework = parseHomeworkList(payload, course, 0).single()

        assertEquals(longBody.take(160).trimEnd(), homework.contentExcerpt)
        assertEquals(longBody, homework.requirementText)
    }

    @Test
    fun parseHomeworkAttachmentsFromPicList() {
        val payload = AppJson.parseToJsonElement(
            """
            {
              "picList": [
                {"id": 123, "file_name": "assignment.pdf", "url": "/ve/file/assignment.pdf", "fileSize": "15 KB"}
              ],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject

        val attachments = parseHomeworkAttachments(payload)

        assertEquals(1, attachments.size)
        assertEquals("123", attachments.first().attachmentId)
        assertEquals("assignment.pdf", attachments.first().filename)
        assertEquals("/ve/file/assignment.pdf", attachments.first().url)
        assertEquals("15 KB", attachments.first().size)
    }

    @Test
    fun parseHomeworkAttachmentsToleratesMissingFieldsAndAlternateShapes() {
        val missingFields = AppJson.parseToJsonElement(
            """
            {
              "picList": [
                {"id": 123},
                {"file_name": "missing-id.pdf"}
              ],
              "unknown": [{"id": 1, "file_name": "ignored.pdf"}]
            }
            """.trimIndent()
        ).jsonObject

        assertTrue(parseHomeworkAttachments(missingFields).isEmpty())

        val alternate = AppJson.parseToJsonElement(
            """
            {
              "data": {"attachments": [{"attachmentId": "a1", "name": "image.png"}]},
              "res": {"files": [{"fileId": "f2", "fileName": "notes.docx"}]}
            }
            """.trimIndent()
        ).jsonObject

        val attachments = parseHomeworkAttachments(alternate)

        assertEquals(setOf("a1", "f2"), attachments.map { it.attachmentId }.toSet())
        assertEquals(setOf("image.png", "notes.docx"), attachments.map { it.filename }.toSet())
    }

    @Test
    fun parseCourseResourcesFixtureShape() {
        val payload = AppJson.parseToJsonElement(
            """
            {
              "bagList": [{"id": 8, "bag_name": "课堂材料"}],
              "resList": [{
                "rpId": 1001,
                "resId": 2001,
                "rpName": "GraphQL.pdf",
                "extName": "PDF",
                "stu_download": 2,
                "play_url": "/rp/2026/03/17/swf/demo.pdf",
                "res_url": "/rp/2026/03/17/demo.pdf"
              }],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject
        val (folders, resources) = parseCourseResourceListing(payload, "0", "experiment", "实验")
        assertEquals("8", folders.first().folderId)
        assertEquals("experiment", folders.first().categoryKey)
        assertEquals("实验", folders.first().categoryLabel)
        assertEquals("1001", resources.first().rpId)
        assertEquals("experiment", resources.first().categoryKey)
        assertEquals("实验", resources.first().categoryLabel)
        assertEquals("/rp/2026/03/17/swf/demo.pdf", resources.first().playUrl)
        assertEquals("/rp/2026/03/17/demo.pdf", resources.first().resUrl)
        assertTrue(resources.first().canDownload)
    }

    @Test
    fun parseCourseResourceTreeKeepsCategoryMetadata() {
        val payload = AppJson.parseToJsonElement(
            """
            {
              "nodes": [{"id": 0, "name": "教案设计"}],
              "STATUS": "2"
            }
            """.trimIndent()
        ).jsonObject

        val folders = parseCourseResourceTree(payload, "lesson_plan", "教案设计")

        assertEquals("0", folders.single().folderId)
        assertEquals("教案设计", folders.single().name)
        assertEquals("lesson_plan", folders.single().categoryKey)
        assertEquals("教案设计", folders.single().categoryLabel)
    }

    @Test
    fun parseCourseReplayLessonsFixtureShape() {
        val payload = AppJson.parseToJsonElement(
            """
            {
              "courseSchedList": [
                {
                  "params": {
                    "videoId": "9E2657F72D5E450FAB4CDF1E6725F15E"
                  },
                  "id": 1774113,
                  "classRoom": "思源楼",
                  "courseId": 129006,
                  "courseName": "软件工程",
                  "courseNum": "M410001B",
                  "teachTimeStr": "20260508",
                  "teacherId": 159966,
                  "teacherName": "王戍",
                  "classBeginTime": "2026-05-08 16:20:00",
                  "classEndTime": "2026-05-08 18:10:00",
                  "uuid": "559F6748116A43D399DE404B5ED28ED6"
                }
              ],
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject

        val lessons = parseCourseReplayLessons(payload)

        assertEquals("1774113", lessons.first().courseSchedId)
        assertEquals("559F6748116A43D399DE404B5ED28ED6", lessons.first().timeTableId)
        assertEquals("9E2657F72D5E450FAB4CDF1E6725F15E", lessons.first().videoId)
        assertEquals("M410001B", lessons.first().courseCode)
        assertTrue(lessons.first().hasVideo)
    }

    @Test
    fun parseCourseReplayPlaybackSortsStreamChoices() {
        val payload = AppJson.parseToJsonElement(
            """
            {
              "res": {
                "streamMap": {
                  "teaStreamHlsUrl": "https://example.edu/teacher.m3u8",
                  "teaStreamUrl": "rtmp://example.edu/teacher",
                  "stuStreamHlsUrl": "https://example.edu/student.m3u8",
                  "vgaStreamHlsUrl": "https://example.edu/screen.m3u8",
                  "vgaStreamUrl": "rtmp://example.edu/screen",
                  "rpSize": "5226.01",
                  "haveStream": "1",
                  "rpStatus": "3"
                },
                "courseSched": {
                  "uuid": "559F6748116A43D399DE404B5ED28ED6"
                }
              },
              "STATUS": "0"
            }
            """.trimIndent()
        ).jsonObject

        val playback = parseCourseReplayPlayback(payload, "1774113", userId = "136786", listenUserId = "23301135")

        assertEquals(listOf("screen", "student", "teacher"), playback.streams.map { it.kind })
        assertEquals("https://example.edu/screen.m3u8", playback.streams.first().hlsUrl)
        assertEquals("5226.01", playback.rpSize)
        assertEquals("23301135", playback.listenUserId)
    }

    @Test
    fun parseCourseReplayPlaybackAllowsEmptyStreamMap() {
        val payload = AppJson.parseToJsonElement("""{"res":{"streamMap":{}},"STATUS":"0"}""").jsonObject

        val playback = parseCourseReplayPlayback(payload, "1774113")

        assertEquals(0, playback.streams.size)
    }

    private fun text(name: String): String {
        val resource = javaClass.getResource("/fixtures/$name")
            ?: error("Missing test fixture: $name")
        return resource.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun json(name: String) = AppJson.parseToJsonElement(text(name)).jsonObject
}
