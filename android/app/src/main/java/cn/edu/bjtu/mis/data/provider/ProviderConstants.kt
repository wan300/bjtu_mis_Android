package cn.edu.bjtu.mis.data.provider

import java.net.URI

object ProviderConstants {
    const val MIS_HOME_URL = "https://mis.bjtu.edu.cn/home/"
    const val MIS_AA_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/10/"
    const val MIS_VE_BRIDGE_URL = "https://mis.bjtu.edu.cn/module/module/104/"
    const val BKSY_VE_BRIDGE_URL = "https://bksycenter.bjtu.edu.cn/NoMasterJumpPage.aspx?URL=jwcZhjx&FPC=page:jwcZhjx"
    const val AA_BASE_URL = "https://aa.bjtu.edu.cn"
    const val AA_TIMETABLE_URL = "$AA_BASE_URL/course_selection/courseselect/stuschedule/"
    const val AA_NOTICE_URL = "$AA_BASE_URL/notice/item/"
    const val VE_BASE_URL = "http://123.121.147.7:88"
    const val VE_KK_PREVIEW_BASE_URL = "http://123.121.147.7:1936/kk"
    const val VE_COURSE_PLATFORM_PATH = "/ve/back/coursePlatform/coursePlatform.shtml"
    const val VE_COURSE_PLATFORM_BASE_URL = "$VE_BASE_URL$VE_COURSE_PLATFORM_PATH"
    const val VE_HOMEWORK_COURSE_TO_PAGE = "10460"
    const val VE_COURSE_RESOURCES_COURSE_TO_PAGE = "10450"
    const val VE_COURSE_REPLAY_COURSE_TO_PAGE = "10480"
    const val VE_COURSE_RESOURCES_DOC_TYPE = "1"
    const val ZHIXING_BASE_URL = "https://zhixing.bjtu.edu.cn"
    const val JOB_BASE_URL = "https://job.bjtu.edu.cn"
    const val JOB_HOME_URL = "$JOB_BASE_URL/frontpage/bjtu/html/index.html"
    const val JOB_GUIDANCE_CATEGORY_ID = "6"
    const val JOB_CONSULT_APPOINTMENT_URL = "http://bjtu.jysd.com/consult"
}

internal fun isBjtuCasLoginUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val path = uri.path.orEmpty()
    return path == "/auth/login" ||
        path.startsWith("/auth/login/") ||
        (uri.host?.lowercase() in setOf("cas.bjtu.edu.cn", "sso.bjtu.edu.cn") &&
            (path.startsWith("/auth/") || path.startsWith("/sso/") || path.startsWith("/o/authorize")))
}

class SessionExpiredException(message: String) : RuntimeException(message)

class SyncAlreadyRunningException(message: String) : RuntimeException(message)
