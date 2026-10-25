package com.wuwa.gachatool

import org.junit.Assert.assertEquals
import org.junit.Test

/** 云同步方向决策的矩阵：这些分支决定会不会用云端快照覆盖本机数据库。 */
class CloudSyncDecisionTest {
    private fun decide(
        baselineEtag: String? = "etag-1",
        baselineHash: String? = "hash-1",
        localHash: String = "hash-1",
        localCount: Int = 120,
        remoteEtag: String = "etag-1",
        strategy: String? = null,
    ) = decideSyncAction(baselineEtag, baselineHash, localHash, localCount, remoteEtag, strategy)

    /**
     * 本机独有变化必须上传，绝不能因为“云端也没变”就把云端快照盖回本机
     * —— 这正是线上丢掉刚导入记录的那条路径。
     */
    @Test fun localOnlyChangesAreUploaded() {
        assertEquals(SyncAction.UploadLocal, decide(baselineHash = "hash-old", localHash = "hash-new"))
        assertEquals(SyncAction.UploadLocal, decide(baselineHash = "hash-old", localHash = "hash-new", strategy = "local"))
    }

    /** 两端都没变时不再重放云端快照；只有用户显式选择才允许覆盖。 */
    @Test fun unchangedBothSidesNeverRewriteLocal() {
        assertEquals(SyncAction.Keep, decide())
        assertEquals(SyncAction.ApplyRemote, decide(strategy = "remote"))
    }

    @Test fun remoteOnlyChangesArePulled() {
        assertEquals(SyncAction.ApplyRemote, decide(remoteEtag = "etag-2"))
    }

    @Test fun bothSidesChangedAskTheUser() {
        assertEquals(SyncAction.BothChangedConflict, decide(baselineHash = "hash-2", remoteEtag = "etag-2"))
        assertEquals(SyncAction.UploadLocal, decide(baselineHash = "hash-2", remoteEtag = "etag-2", strategy = "local"))
        assertEquals(SyncAction.ApplyRemote, decide(baselineHash = "hash-2", remoteEtag = "etag-2", strategy = "remote"))
    }

    @Test fun firstConnectWithBothSidesPopulatedAsksTheUser() {
        assertEquals(SyncAction.FirstConnectConflict, decide(baselineEtag = null, baselineHash = null, localHash = "hash-local"))
        assertEquals(SyncAction.UploadLocal, decide(baselineEtag = null, baselineHash = null, localHash = "hash-local", strategy = "local"))
        assertEquals(SyncAction.ApplyRemote, decide(baselineEtag = null, baselineHash = null, localHash = "hash-local", strategy = "remote"))
    }

    @Test fun firstConnectWithEmptyLocalPullsTheCloud() {
        assertEquals(
            SyncAction.ApplyRemote,
            decide(baselineEtag = null, baselineHash = null, localHash = "hash-empty", localCount = 0),
        )
    }
}