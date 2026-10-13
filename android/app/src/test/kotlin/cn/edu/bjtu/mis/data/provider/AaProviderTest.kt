package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import cn.edu.bjtu.mis.model.DefaultCourseSelectionGroupNames
import cn.edu.bjtu.mis.model.ScoreItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AaProviderTest {
    @Test
    fun emptyRoomQuerySendsAndPreservesClearedFilters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("empty_rooms.html")))
            server.start()

            val envelope = provider(server).fetchEmptyRooms(week = "8", building = "", room = "")

            val url = server.takeRequest().requestUrl!!
            assertEquals("/classroom/timeholdresult/room_view/", url.encodedPath)
            assertEquals("8", url.queryParameter("zc"))
            assertEquals("", url.queryParameter("jxlh"))
            assertEquals("", url.queryParameter("jash"))
            assertEquals("", envelope.data.query["building"])
            assertEquals("", envelope.sourceParams["building"]?.jsonPrimitive?.content)
            assertEquals("", envelope.sourceParams["room"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun mergeScoreItemsDeduplicatesAcrossProgressiveTerms() {
        val duplicate = score("2025-2026-1", "软件工程", "90")
        val merged = mergeScoreItems(
            listOf(
                duplicate,
                score("2025-2026-1", "软件工程", "90"),
                score("2025-2026-2", "软件工程", "91"),
            )
        )

        assertEquals(listOf(duplicate, score("2025-2026-2", "软件工程", "91")), merged)
    }

    @Test
    fun fetchScoresLoadsPerCourseDetailsInline() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("scores_main.html")))
            server.enqueue(MockResponse().setBody(text("score_detail.html")))
            server.enqueue(MockResponse().setBody(text("score_detail_label_value.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchScores(ctype = "lr")

            val first = envelope.data.items.first()
            assertNull(first.detail)
            assertEquals(listOf("平时", "40%", "88"), first.detailData!!.tables.first().rows.first())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun fetchHistoryScoresSkipsPerCourseDetails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("scores_main.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchHistoryScores("2025-2026-2-2")

            assertNull(envelope.data.items.first().detailData)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionReadsCourses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertEquals("course_selection", envelope.module)
            assertTrue(envelope.data.canSubmit)
            assertEquals("M410003B_01", envelope.data.availableCourses.first().key)
            assertEquals("/course_selection/courseselecttask/selects/", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionReadsSelectsActionWhenShellIsEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertEquals("course_selection", envelope.module)
            assertTrue(envelope.data.canSubmit)
            assertEquals("A101005B_01", envelope.data.availableCourses.first().key)
            assertEquals("A101001B_01", envelope.data.selectedCourses.single().key)
            assertEquals("/course_selection/courseselecttask/selects/", server.takeRequest().path)
            val actionRequest = server.takeRequest()
            assertMisLoadRequest(actionRequest.path)
            assertEquals("XMLHttpRequest", actionRequest.getHeader("X-Requested-With"))
        }
    }

    @Test
    fun fetchCourseSelectionReadsPaginatedSelectsActionPages() = runBlocking {
        MockWebServer().use { server ->
            val firstPage = paginatedSelectsActionHtml(
                text("course_selection_selects_action.html"),
                currentPage = 1,
                totalPages = 2,
                totalRecords = 4,
            )
            val secondPage = paginatedSelectsActionHtml(
                text("course_selection_selects_action.html")
                    .replace("A101005B", "A101006B")
                    .replace("101005", "101006"),
                currentPage = 2,
                totalPages = 2,
                totalRecords = 4,
            )
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(firstPage))
            server.enqueue(MockResponse().setBody(secondPage))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            val keys = envelope.data.availableCourses.map { it.key }.toSet()
            assertTrue(keys.contains("A101005B_01"))
            assertTrue(keys.contains("A101006B_01"))
            assertEquals("/course_selection/courseselecttask/selects/", server.takeRequest().path)
            val firstRequest = server.takeRequest()
            assertMisLoadRequest(firstRequest.path)
            val pageRequest = server.takeRequest()
            assertTrue(pageRequest.path.orEmpty().contains("page=2"))
            assertTrue(pageRequest.path.orEmpty().contains("perpage=500"))
        }
    }

    @Test
    fun fetchCourseSelectionReadsGroupNamesFromSelectsActionWithoutProbing() = runBlocking {
        MockWebServer().use { server ->
            val groupedHtml = text("course_selection_selects_action.html").replace(
                "<form method=\"get\">",
                """
                <form method="get">
                  <select name="gname">
                    <option value="Group A">Group A</option>
                  </select>
                """.trimIndent(),
            )
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(groupedHtml))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertEquals(listOf("Group A"), envelope.data.courseGroupNames)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionKeepsDiscoveredGroupNamesWhenGroupPageIsEmpty() = runBlocking {
        MockWebServer().use { server ->
            val groupedHtml = text("course_selection_selects_action.html").replace(
                "<form method=\"get\">",
                """
                <form method="get">
                  <select name="gname">
                    <option value="Group A">Group A</option>
                  </select>
                """.trimIndent(),
            )
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(groupedHtml))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertEquals(listOf("Group A"), envelope.data.courseGroupNames)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionReadsGroupNamesFromSelectsShell() = runBlocking {
        MockWebServer().use { server ->
            val shellHtml = text("course_selection_empty_shell.html").replace(
                "</body>",
                """
                  <select id="gname">
                    <option>-- 课组名称 --</option>
                    <option value="11">Shell Group</option>
                  </select>
                </body>
                """.trimIndent(),
            )
            server.enqueue(MockResponse().setBody(shellHtml))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action.html")))
            server.enqueue(MockResponse().setBody(groupedSelectsActionHtml("Shell Group", groupValue = "11")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()
            val groupedEnvelope = provider(server).fetchCourseSelectionGroup("Shell Group")

            assertEquals(
                "Shell Group",
                groupedEnvelope.data.availableCourses.first { it.key == "A101005B_01" }.groupName,
            )
            assertEquals(listOf("Shell Group"), envelope.data.courseGroupNames)
            repeat(2) { server.takeRequest() }
            val groupRequest = server.takeRequest()
            assertTrue(groupRequest.path.orEmpty().contains("gname=11"))
            assertEquals("XMLHttpRequest", groupRequest.getHeader("X-Requested-With"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionReadsGroupNamesFromLabeledSelects() = runBlocking {
        MockWebServer().use { server ->
            val groupLabel = "\u8bfe\u7ec4\u540d\u79f0"
            val shellHtml = text("course_selection_empty_shell.html").replace(
                "</body>",
                """
                  <select title="-- $groupLabel --">
                    <option>-- $groupLabel --</option>
                    <option>Label Group</option>
                  </select>
                </body>
                """.trimIndent(),
            )
            server.enqueue(MockResponse().setBody(shellHtml))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertEquals(listOf("Label Group"), envelope.data.courseGroupNames)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionAnnotatesShellCoursesWhenShellHasGroupNames() = runBlocking {
        MockWebServer().use { server ->
            val shellHtml = text("course_selection.html").replace(
                "<form method=\"post\" action=\"/course_selection/courseselecttask/submit/\">",
                """
                <form method="post" action="/course_selection/courseselecttask/submit/">
                  <select name="gname">
                    <option value="Shell Group">Shell Group</option>
                  </select>
                """.trimIndent(),
            )
            server.enqueue(MockResponse().setBody(shellHtml))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertTrue(envelope.data.courseGroupNames.contains("Shell Group"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionDoesNotProbeWhenShellCourseTableHasActions() = runBlocking {
        MockWebServer().use { server ->
            val shellHtml = largeCourseSelectionShell()
            server.enqueue(MockResponse().setBody(shellHtml))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertTrue(envelope.data.canSubmit)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionUsesFallbackGroupNamesForLargeUngroupedShell() = runBlocking {
        MockWebServer().use { server ->
            val fallbackGroup = "\u521b\u65b0\u521b\u4e1a\u7d20\u517b\u7c7b\u8bfe\u7a0b"
            server.enqueue(MockResponse().setBody(largeCourseSelectionShell()))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertTrue(envelope.data.courseGroupNames.contains(fallbackGroup))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionReadsGroupNamesFromBootstrapDropdown() = runBlocking {
        MockWebServer().use { server ->
            val shellHtml = text("course_selection_empty_shell.html").replace(
                "</body>",
                """
                  <div class="bootstrap-select">
                    <button title="-- 课组名称 --">-- 课组名称 --</button>
                    <ul class="dropdown-menu">
                      <li><a><span class="text">Bootstrap Group</span></a></li>
                    </ul>
                  </div>
                </body>
                """.trimIndent(),
            )
            server.enqueue(MockResponse().setBody(shellHtml))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action.html")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelection()

            assertTrue(envelope.data.courseGroupNames.contains("Bootstrap Group"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetchCourseSelectionGroupUsesFallbackNumericGname() = runBlocking {
        MockWebServer().use { server ->
            val groupName = DefaultCourseSelectionGroupNames[10]
            server.enqueue(MockResponse().setBody(groupedSelectsActionHtml(groupName, groupValue = "11")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelectionGroup(groupName)

            assertEquals(groupName, envelope.data.availableCourses.single().groupName)
            assertTrue(envelope.data.courseGroupNames.contains(groupName))
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().contains("gname=11"))
            assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        }
    }

    @Test
    fun fetchCourseSelectionQueryUsesMisFilterParams() = runBlocking {
        MockWebServer().use { server ->
            val groupName = DefaultCourseSelectionGroupNames[10]
            server.enqueue(MockResponse().setBody(groupedSelectsActionHtml(groupName, groupValue = "11")))
            server.start()
            val provider = provider(server)

            val envelope = provider.fetchCourseSelectionQuery(
                groupName = groupName,
                courseQuery = "A101005B",
                sectionQuery = "01",
            )

            assertEquals(groupName, envelope.data.availableCourses.single().groupName)
            val request = server.takeRequest()
            val path = request.path.orEmpty()
            assertTrue(path.contains("/course_selection/courseselecttask/selects_action/?"))
            assertTrue(path.contains("gname=11"))
            assertTrue(path.contains("kch=A101005B"))
            assertTrue(path.contains("kxh=01"))
            assertTrue(path.contains("action=load"))
            assertTrue(path.contains("order="))
            assertTrue(path.contains("iframe=school"))
            assertTrue(path.contains("submit="))
            assertTrue(path.contains("has_advance_query="))
            assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
        }
    }

    @Test
    fun attemptCourseSelectionsUsesTargetGroupContext() = runBlocking {
        MockWebServer().use { server ->
            val groupName = DefaultCourseSelectionGroupNames[10]
            server.enqueue(MockResponse().setBody(groupedSelectsActionHtml(groupName, groupValue = "11")))
            server.enqueue(MockResponse().setBody(captchaRefreshJson("hash-token")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-action"))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelections(
                listOf(
                    CourseSelectionTarget(
                        key = "A101005B_01",
                        courseName = "A101005B:Electrical Training 01",
                        groupName = groupName,
                    ),
                ),
            )

            assertEquals("captcha_required", result.status)
            val listingRequest = server.takeRequest()
            assertTrue(listingRequest.path.orEmpty().contains("/course_selection/courseselecttask/selects_action/?"))
            assertTrue(listingRequest.path.orEmpty().contains("gname=11"))
            assertEquals("/captcha/refresh/", server.takeRequest().path)
            assertEquals("/captcha/image/hash-token/", server.takeRequest().path)
        }
    }

    @Test
    fun attemptCourseSelectionPostsParsedForm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(selectedHtml()))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelection(courseKey = "M410003B_01")

            assertEquals("success", result.status)
            server.takeRequest()
            val submit = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/submit/", submit.path)
            val body = submit.body.readUtf8()
            assertTrue(body.contains("csrfmiddlewaretoken=csrf-token"))
            assertTrue(body.contains("selects=target-1"))
        }
    }

    @Test
    fun attemptCourseSelectionPostsFormFromDetectedCourseTable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection_detected_table.html")))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(selectedDetectedHtml()))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelection(courseKey = "M500001B_02")

            assertEquals("success", result.status)
            server.takeRequest()
            val submit = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/submit/", submit.path)
            val body = submit.body.readUtf8()
            assertTrue(body.contains("csrfmiddlewaretoken=csrf-token"))
            assertTrue(body.contains("selects=late-checkbox"))
        }
    }

    @Test
    fun attemptCourseSelectionFromSelectsActionReturnsCaptchaBeforeSubmit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action.html")))
            server.enqueue(MockResponse().setBody(captchaRefreshJson("hash-token")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-action"))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelection(courseKey = "A101005B_01")

            assertEquals("captcha_required", result.status)
            assertTrue(result.captchaChallenge?.imageDataUrl?.startsWith("data:image/png;base64,") == true)
            assertEquals("/course_selection/courseselecttask/selects/", server.takeRequest().path)
            assertMisLoadRequest(server.takeRequest().path)
            assertEquals("/captcha/refresh/", server.takeRequest().path)
            assertEquals("/captcha/image/hash-token/", server.takeRequest().path)
        }
    }

    @Test
    fun submitCourseSelectionCaptchaPostsSelectsActionFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action.html")))
            server.enqueue(MockResponse().setBody(captchaRefreshJson("hash-token")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-action"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action_selected.html")))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelection(courseKey = "A101005B_01").captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "1234")

            assertEquals("success", result.status)
            repeat(4) { server.takeRequest() }
            val submit = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/selects_action/?action=submit", submit.path)
            val body = submit.body.readUtf8()
            assertTrue(body.contains("checkboxs=101005"))
            assertTrue(body.contains("hashkey=hash-token"))
            assertTrue(body.contains("answer=1234"))
        }
    }

    @Test
    fun attemptCourseSelectionsPostsDuplicateCheckboxFieldsAfterCaptcha() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(
                text("course_selection_selects_action.html").replace(
                    "</table>",
                    """
                    <tr>
                      <td><input type="checkbox" name="checkboxs" value="101006" /></td>
                      <td>4</td>
                      <td>A101006B:工程训练 01</td>
                      <td>2</td>
                      <td>1.0</td>
                      <td>考查</td>
                      <td>教师 E</td>
                      <td>第01-08周 星期五 第1节</td>
                      <td></td>
                    </tr>
                    </table>
                    """.trimIndent(),
                )
            ))
            server.enqueue(MockResponse().setBody(captchaRefreshJson("hash-token")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-action"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(
                text("course_selection_selects_action_selected.html").replace(
                    "</table>",
                    """
                    <tr>
                      <td></td>
                      <td>A101006B:工程训练 01</td>
                      <td>2</td>
                      <td>16.0 / 1.0</td>
                      <td>任选</td>
                      <td>选中</td>
                      <td>教师 E</td>
                      <td>第01-08周 星期五 第1节</td>
                    </tr>
                    </table>
                    """.trimIndent(),
                )
            ))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelections(
                listOf(
                    CourseSelectionTarget("A101005B_01", "A101005B:电类工程素质训练 01"),
                    CourseSelectionTarget("A101006B_01", "A101006B:工程训练 01"),
                )
            ).captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "1234")

            assertEquals("success", result.status)
            repeat(4) { server.takeRequest() }
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("checkboxs=101005"))
            assertTrue(body.contains("checkboxs=101006"))
        }
    }

    @Test
    fun submitCourseSelectionCaptchaReportsPartiallyCompletedTargets() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(
                text("course_selection_selects_action.html").replace(
                    "</table>",
                    """
                    <tr>
                      <td><input type="checkbox" name="checkboxs" value="101006" /></td>
                      <td>4</td>
                      <td>A101006B:工程训练 01</td>
                      <td>2</td>
                      <td>1.0</td>
                      <td>考查</td>
                      <td>教师 E</td>
                      <td>第01-08周 星期五 第1节</td>
                      <td></td>
                    </tr>
                    </table>
                    """.trimIndent(),
                )
            ))
            server.enqueue(MockResponse().setBody(captchaRefreshJson("hash-token")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-action"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(text("course_selection_selects_action_selected.html")))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelections(
                listOf(
                    CourseSelectionTarget("A101005B_01", "A101005B:电类工程素质训练 01"),
                    CourseSelectionTarget("A101006B_01", "A101006B:工程训练 01"),
                )
            ).captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "1234")

            assertEquals("submitted", result.status)
            assertEquals(listOf("A101005B_01"), result.completedCourseKeys)
        }
    }

    @Test
    fun attemptCourseSelectionsSkipsFullCoursesAndSubmitsSelectableTargets() = runBlocking {
        MockWebServer().use { server ->
            val mixedAvailabilityHtml = text("course_selection_selects_action.html")
                .replaceFirst(
                    Regex("""<td>1</td>\s*<td>1\.0</td>"""),
                    """
                      <td>0</td>
                      <td>1.0</td>
                    """.trimIndent(),
                )
                .replace(
                    "</table>",
                    """
                    <tr>
                      <td><input type="checkbox" name="checkboxs" value="101006" /></td>
                      <td>4</td>
                      <td>A101006B:工程训练 01</td>
                      <td>2</td>
                      <td>1.0</td>
                      <td>考查</td>
                      <td>教师 E</td>
                      <td>第 1-08 周 星期五 第 1 节</td>
                      <td></td>
                    </tr>
                    </table>
                    """.trimIndent(),
                )
            val selectedSecondHtml = text("course_selection_selects_action_selected.html")
                .replace("A101005B", "A101006B")
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(mixedAvailabilityHtml))
            server.enqueue(MockResponse().setBody(captchaRefreshJson("hash-token")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-action"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody(text("course_selection_empty_shell.html")))
            server.enqueue(MockResponse().setBody(selectedSecondHtml))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelections(
                listOf(
                    CourseSelectionTarget("A101005B_01", "A101005B:电类工程素质训练 01"),
                    CourseSelectionTarget("A101006B_01", "A101006B:工程训练 01"),
                )
            ).captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "1234")

            assertEquals("success", result.status)
            assertEquals(listOf("A101006B_01"), result.completedCourseKeys)
            repeat(4) { server.takeRequest() }
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("checkboxs=101006"))
            assertTrue(!body.contains("checkboxs=101005"))
        }
    }

    @Test
    fun attemptCourseSelectionReturnsCaptchaChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody(captchaHtml("1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-1"))
            server.start()
            val provider = provider(server)

            val result = provider.attemptCourseSelection(courseKey = "M410003B_01")

            assertEquals("captcha_required", result.status)
            assertTrue(result.captchaChallenge?.imageDataUrl?.startsWith("data:image/png;base64,") == true)
            server.takeRequest()
            server.takeRequest()
            assertEquals("/captcha/image/1.png", server.takeRequest().path)
        }
    }

    @Test
    fun submitCourseSelectionCaptchaCanSucceed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody(captchaHtml("1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-1"))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(selectedHtml()))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelection(courseKey = "M410003B_01").captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "1234")

            assertEquals("success", result.status)
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            val captchaSubmit = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/captcha/", captchaSubmit.path)
            assertTrue(captchaSubmit.body.readUtf8().contains("captcha=1234"))
        }
    }

    @Test
    fun submitCourseSelectionCaptchaCanReturnNextChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody(captchaHtml("1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-1"))
            server.enqueue(MockResponse().setBody(captchaHtml("2")))
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-2"))
            server.start()
            val provider = provider(server)

            val challenge = provider.attemptCourseSelection(courseKey = "M410003B_01").captchaChallenge!!
            val result = provider.submitCourseSelectionCaptcha(challenge.challengeId, "wrong")

            assertEquals("captcha_required", result.status)
            assertTrue(result.captchaChallenge?.challengeId != challenge.challengeId)
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            assertEquals("/captcha/image/2.png", server.takeRequest().path)
        }
    }

    @Test
    fun dropCourseSelectionPostsDeleteForm() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(text("course_selection.html")))
            server.enqueue(MockResponse().setBody("<div class='alert'>ok</div>"))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false)))
            server.start()
            val provider = provider(server)

            val result = provider.dropCourseSelection(courseKey = "M310005B_01")

            assertEquals("drop_success", result.status)
            server.takeRequest()
            val drop = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/delete/", drop.path)
            val body = drop.body.readUtf8()
            assertTrue(body.contains("csrfmiddlewaretoken=csrf-token"))
            assertTrue(body.contains("pk=selected-1"))
        }
    }

    @Test
    fun replaceCourseSelectionDropsThenSelectsTarget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = true, selectedTarget = false)))
            server.enqueue(MockResponse().setBody("<div class='alert'>drop ok</div>"))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false, selectedTarget = false)))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false, selectedTarget = false)))
            server.enqueue(MockResponse().setBody("<div class='alert'>select ok</div>"))
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = false, selectedTarget = true)))
            server.start()
            val provider = provider(server)

            val result = provider.replaceCourseSelection(
                targetCourseKey = "M410003B_01",
                dropCourseKey = "M310005B_01",
            )

            assertEquals("replace_success", result.status)
            server.takeRequest()
            assertEquals("/course_selection/courseselecttask/delete/", server.takeRequest().path)
            server.takeRequest()
            server.takeRequest()
            val select = server.takeRequest()
            assertEquals("/course_selection/courseselecttask/submit/", select.path)
            assertTrue(select.body.readUtf8().contains("selects=target-1"))
        }
    }

    @Test
    fun replaceCourseSelectionTargetNoRemainingDoesNotDrop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(replaceHtml(selectedOs = true, selectedTarget = false, targetRemaining = 0)))
            server.start()
            val provider = provider(server)

            val result = provider.replaceCourseSelection(
                targetCourseKey = "M410003B_01",
                dropCourseKey = "M310005B_01",
            )

            assertEquals("target_no_remaining", result.status)
            assertEquals(1, server.requestCount)
        }
    }

    private fun provider(server: MockWebServer): AaProvider {
        val baseUrl = server.url("/").toString().trimEnd('/')
        return AaProvider(BjtuHttpClient(AppCookieJar()), aaBaseUrl = baseUrl)
    }

    private fun missingActionResponse(): MockResponse =
        MockResponse().setBody("")

    private fun assertMisLoadRequest(path: String?) {
        val requestPath = path.orEmpty()
        assertTrue(requestPath.startsWith("/course_selection/courseselecttask/selects_action/?"))
        listOf(
            "gname=",
            "kch=",
            "kxh=",
            "action=load",
            "order=",
            "iframe=school",
            "submit=",
            "has_advance_query=",
            "perpage=500",
        ).forEach { part ->
            assertTrue("Missing $part in $requestPath", requestPath.contains(part))
        }
    }

    private fun score(term: String, courseName: String, score: String): ScoreItem =
        ScoreItem(
            term = term,
            courseName = courseName,
            credit = "2",
            score = score,
            bonusScore = null,
            teacher = "Teacher",
            detail = null,
            detailPath = null,
        )

    private fun text(name: String): String {
        val resource = javaClass.getResource("/fixtures/$name")
            ?: error("Missing test fixture: $name")
        return resource.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun largeCourseSelectionShell(): String {
        val extraRows = (0 until 51).joinToString("\n") { index ->
            """
            <tr>
              <td><input type="checkbox" name="selects" value="probe-$index" /></td>
              <td>M99${index.toString().padStart(3, '0')}B Probe Course $index 01 School</td>
              <td>1</td><td>1</td><td>Optional</td><td>Essay</td><td>Teacher P</td><td>Fri 1-2 Room 505</td><td></td>
            </tr>
            """.trimIndent()
        }
        return text("course_selection.html").replace(
            "      </table>\n      <a class=\"btn btn-primary\" href=\"#\">Submit</a>",
            "      $extraRows\n      </table>\n      <a class=\"btn btn-primary\" href=\"#\">Submit</a>",
        )
    }

    private fun groupedSelectsActionHtml(
        groupName: String,
        groupValue: String = groupName,
        courseCell: String = "A101005B:Electrical Training 01",
        checkbox: String = "101005",
        teacher: String = "Teacher G",
    ): String = """
        <html>
          <body>
            <form method="get">
              <select name="gname">
                <option value="">-- Course Group --</option>
                <option value="$groupValue">$groupName</option>
              </select>
              <a id="select-submit-btn" class="btn btn-primary" href="#">Submit</a>
              <table class="table table-bordered">
                <tr>
                  <th>Select</th><th>Index</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Exam</th><th>Teacher</th><th>Time</th><th>Note</th>
                </tr>
                <tr>
                  <td><input type="checkbox" name="checkboxs" value="$checkbox" /></td>
                  <td>1</td>
                  <td>$courseCell</td>
                  <td>1</td>
                  <td>1.0</td>
                  <td>Check</td>
                  <td>$teacher</td>
                  <td>Fri 1-2 Room 505</td>
                  <td></td>
                </tr>
              </table>
            </form>
          </body>
        </html>
    """.trimIndent()

    private fun paginatedSelectsActionHtml(
        html: String,
        currentPage: Int,
        totalPages: Int,
        totalRecords: Int,
    ): String = html.replace(
        "</body>",
        """
          <ul class="pagination">
            <li class="disabled"><a>页次：$currentPage/$totalPages，共计${totalRecords}条记录</a></li>
          </ul>
          <input id="thepage" name="page" value="$currentPage" />
          <select id="theperpage" name="perpage">
            <option>20</option>
            <option selected>500</option>
          </select>
        </body>
        """.trimIndent(),
    )

    private fun captchaHtml(id: String): String = """
        <html><body>
          <div class="modal">
            <form method="post" action="/course_selection/courseselecttask/captcha/">
              <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
              <img src="/captcha/image/$id.png" />
              <input type="text" name="captcha" />
            </form>
          </div>
        </body></html>
    """.trimIndent()

    private fun captchaRefreshJson(key: String): String =
        """{"key":"$key","image_url":"/captcha/image/$key/"}"""

    private fun selectedHtml(): String = """
        <html><body>
          <form method="post" action="/course_selection/courseselecttask/submit/">
            <div id="selected-container">
              <table class="table table-bordered">
                <tr><th>Action</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
                <tr><td></td><td>M410003B Platform Software Design 01 Software School</td><td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td></tr>
              </table>
            </div>
            <table class="table table-bordered"><tr><th>Other</th><th>Value</th></tr></table>
            <table class="table table-bordered">
              <tr><th>Select</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
              <tr><td>Selected</td><td>M410003B Platform Software Design 01 Software School</td><td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td></tr>
            </table>
            <a class="btn btn-primary" href="#">Submit</a>
          </form>
        </body></html>
    """.trimIndent()

    private fun selectedDetectedHtml(): String = """
        <html><body>
          <form method="post" action="/course_selection/courseselecttask/submit/">
            <div id="selected-container">
              <table class="table table-bordered">
                <tr><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th><th>Action</th></tr>
                <tr><td>M500001B Data Mining 02 School</td><td>2</td><td>2</td><td>Optional</td><td>Exam</td><td>Teacher D</td><td>Thu 1-2 Room 404</td><td></td></tr>
              </table>
            </div>
            <table class="table table-bordered"><tr><th>Meta</th><th>Value</th></tr></table>
            <table class="table table-bordered">
              <tr><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th><th>Select</th></tr>
              <tr><td>M500001B Data Mining 02 School</td><td>2</td><td>2</td><td>Optional</td><td>Exam</td><td>Teacher D</td><td>Thu 1-2 Room 404</td><td>Selected</td></tr>
            </table>
            <a class="btn btn-primary" href="#">Submit</a>
          </form>
        </body></html>
    """.trimIndent()

    private fun replaceHtml(
        selectedOs: Boolean = true,
        selectedTarget: Boolean = false,
        targetRemaining: Int = 2,
    ): String {
        val selectedRows = buildString {
            if (selectedOs) {
                append(
                    """
                    <tr>
                      <td><a class="select-delete-btn" data-pk="selected-1">Delete</a></td>
                      <td>M310005B Operating Systems 01 Software School</td>
                      <td>1</td><td>3</td><td>Required</td><td>Exam</td><td>Teacher A</td><td>Mon 1-2 Room 101</td>
                    </tr>
                    """.trimIndent(),
                )
            }
            if (selectedTarget) {
                append(
                    """
                    <tr>
                      <td><a class="select-delete-btn" data-pk="target-1">Delete</a></td>
                      <td>M410003B Platform Software Design 01 Software School</td>
                      <td>1</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
                    </tr>
                    """.trimIndent(),
                )
            }
        }
        val osAvailable = if (selectedOs) "" else """
            <tr>
              <td><input type="checkbox" name="selects" value="selected-1"></td>
              <td>M310005B Operating Systems 01 Software School</td>
              <td>1</td><td>3</td><td>Required</td><td>Exam</td><td>Teacher A</td><td>Mon 1-2 Room 101</td>
            </tr>
        """.trimIndent()
        val targetAction = if (selectedTarget) {
            "Selected"
        } else {
            """<input type="checkbox" name="selects" value="target-1">"""
        }
        return """
            <html><body>
              <form method="post" action="/course_selection/courseselecttask/submit/">
                <input type="hidden" name="csrfmiddlewaretoken" value="csrf-token" />
                <div id="selected-container">
                  <table class="table table-bordered">
                    <tr><th>Action</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
                    $selectedRows
                  </table>
                </div>
                <table class="table table-bordered"><tr><th>Other</th><th>Value</th></tr></table>
                <table class="table table-bordered">
                  <tr><th>Select</th><th>Course</th><th>Remaining</th><th>Credit</th><th>Type</th><th>Exam</th><th>Teacher</th><th>Time</th></tr>
                  $osAvailable
                  <tr>
                    <td>$targetAction</td>
                    <td>M410003B Platform Software Design 01 Software School</td>
                    <td>$targetRemaining</td><td>2</td><td>Optional</td><td>Essay</td><td>Teacher B</td><td>Tue 3-4 Room 202</td>
                  </tr>
                </table>
                <a class="btn btn-primary" href="#">Submit</a>
              </form>
            </body></html>
        """.trimIndent()
    }
}
