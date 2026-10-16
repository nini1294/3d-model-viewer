package space.nish.stlviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileLocationHelperTest {

    @Test
    fun testFileLocationInfoDirectPath() {
        val info = FileLocationInfo(
            displayPath = "/storage/emulated/0/Models/gear.stl",
            rawUri = null,
        )
        assertEquals("/storage/emulated/0/Models/gear.stl", info.displayPath)
        assertNull(info.rawUri)
    }

    @Test
    fun testFileLocationInfoWithRawUri() {
        val info = FileLocationInfo(
            displayPath = "/storage/emulated/0/Download/sphere.obj",
            rawUri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsphere.obj",
        )
        assertEquals("/storage/emulated/0/Download/sphere.obj", info.displayPath)
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsphere.obj",
            info.rawUri,
        )
    }
}
