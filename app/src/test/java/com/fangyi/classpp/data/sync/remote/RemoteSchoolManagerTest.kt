package com.fangyi.classpp.data.sync.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** 拉取结果聚合：只有三个计数全 0 才是「已是最新」，否则按实际变化提示 */
class RemoteSchoolManagerTest {

    @Test
    fun `no changes at all is up to date`() {
        assertEquals(RemoteRefreshOutcome.UpToDate, remoteRefreshOutcome(0, 0, 0))
    }

    @Test
    fun `added schools are reported`() {
        assertEquals(
            RemoteRefreshOutcome.Refreshed(added = 2, changed = 0, skipped = 0),
            remoteRefreshOutcome(2, 0, 0),
        )
    }

    @Test
    fun `changed and skipped counts are reported separately`() {
        assertEquals(
            RemoteRefreshOutcome.Refreshed(added = 1, changed = 1, skipped = 1),
            remoteRefreshOutcome(1, 1, 1),
        )
    }

    @Test
    fun `skipped only is not up to date`() {
        assertEquals(
            RemoteRefreshOutcome.Refreshed(added = 0, changed = 0, skipped = 1),
            remoteRefreshOutcome(0, 0, 1),
        )
    }
}
