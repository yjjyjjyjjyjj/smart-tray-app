package kr.ac.gachon.smarttray.bluetooth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryPublicationTest {
    @Test fun observerPatchCannotDeliverOldSnapshotAfterNewConfigState() {
        val received = mutableListOf<Int>()
        val service: (JSONObject) -> Unit = {
            if (it.optInt("qaRevision", -1) == 0) TrayRepository.patch { next -> next.put("qaRevision", 1) }
        }
        val webView: (JSONObject) -> Unit = { received.add(it.getInt("qaRevision")) }
        TrayRepository.observe(service)
        TrayRepository.observe(webView)
        try {
            TrayRepository.patch { it.put("qaRevision", 0) }
            assertEquals(listOf(0, 1), received)
            assertEquals(1, TrayRepository.snapshot().getInt("qaRevision"))
        } finally {
            TrayRepository.removeObserver(service); TrayRepository.removeObserver(webView)
            TrayRepository.patch { it.remove("qaRevision") }
        }
    }
}
