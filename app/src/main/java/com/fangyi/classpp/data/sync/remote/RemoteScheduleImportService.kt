package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.sync.ImportBlockReason
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.ImportSemester
import com.fangyi.classpp.data.sync.ScheduleImportService
import com.fangyi.classpp.data.sync.SemestersOutcome
import com.fangyi.classpp.data.sync.SyncState
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import com.fangyi.classpp.data.sync.mapState
import kotlinx.coroutines.flow.StateFlow

/**
 * 远端声明学校的教务导入服务（WebView-only）：
 * - 没有原生登录/保存凭据，连接后 [blockedReason] 恒为 `NoCredentials`，
 *   教务导入浮层据此显示「从教务网页导入」入口；
 * - `fetchSemesters` / `fetchSchedule` 是原生凭据路径，远端学校不支持，返回 `NeedLogin`；
 * - 真正的导入走课表页固定脚本 → [RemoteTimetableMapper]。
 */
internal class RemoteScheduleImportService(
    syncState: StateFlow<SyncState>,
    private val timetable: DeclarationTimetable,
    private val fallbackName: String,
    override val webViewTimetable: WebViewTimetableSpec,
    private val today: () -> IsoDate = IsoDate::today,
) : ScheduleImportService {

    override val blockedReason: StateFlow<ImportBlockReason?> =
        syncState.mapState { state ->
            if (state.uid.isEmpty()) ImportBlockReason.NotConnected
            else ImportBlockReason.NoCredentials
        }

    override suspend fun fetchSemesters(): SemestersOutcome =
        SemestersOutcome.Failed(ImportError.NeedLogin)

    override suspend fun fetchSchedule(semester: ImportSemester): ImportOutcome =
        ImportOutcome.Failed(ImportError.NeedLogin)

    override fun importFromWebTimetable(raw: String): ImportOutcome =
        RemoteTimetableMapper.toImportOutcome(raw, timetable, fallbackName, today())
}
