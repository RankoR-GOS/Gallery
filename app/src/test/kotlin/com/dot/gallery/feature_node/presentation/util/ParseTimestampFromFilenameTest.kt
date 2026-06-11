package com.dot.gallery.feature_node.presentation.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * Regression tests for [parseTimestampFromFilename].
 *
 * See bug #920: Facebook export photos (long numeric IDs joined by underscores)
 * were being parsed as future dates because the date regex latched onto digits
 * embedded inside the IDs.
 */
class ParseTimestampFromFilenameTest {

    @Test
    fun parsesStandardCameraFilenames() {
        assertNotNull("IMG_20180508_213737.jpg".parseTimestampFromFilename())
        assertNotNull("VID_20180508_213737.mp4".parseTimestampFromFilename())
        assertNotNull("PXL_20180508_213737.jpg".parseTimestampFromFilename())
        assertNotNull("Screenshot_20210101-120000.png".parseTimestampFromFilename())
    }

    @Test
    fun parsesCameraFilenamesWithMilliseconds() {
        // Pixel Camera appends a 3-digit millisecond suffix.
        assertNotNull("PXL_20250317_002200191.jpg".parseTimestampFromFilename())
        assertNotNull("PXL_20251106_155541857.NIGHT.jpg".parseTimestampFromFilename())
        assertNotNull("IMG_20200614_155431892.jpg".parseTimestampFromFilename())
    }

    @Test
    fun ignoresDigitsEmbeddedInLongerNumericRuns() {
        assertNull("9920180508_213737.jpg".parseTimestampFromFilename())
        assertNull("IMG_20180508_2137371234.jpg".parseTimestampFromFilename())
        assertNull("20180508_21373799.jpg".parseTimestampFromFilename())
        // Facebook export style filenames: long numeric IDs separated by underscores.
        // None of these should yield a (future) date.
        assertNull("462051234_101601234567890_1234567890123456789_n.jpg".parseTimestampFromFilename())
        assertNull("123456789_10157012345678901_o.jpg".parseTimestampFromFilename())
        assertNull("FB_IMG_1623456789.jpg".parseTimestampFromFilename())
    }

    @Test
    fun rejectsFutureDates() {
        val futureYear = Calendar.getInstance().get(Calendar.YEAR) + 5
        assertNull("IMG_${futureYear}1201_120000.jpg".parseTimestampFromFilename())
    }

    @Test
    fun rejectsInvalidCalendarValues() {
        assertNull("IMG_20181308_213737.jpg".parseTimestampFromFilename()) // month 13
        assertNull("IMG_20180532_213737.jpg".parseTimestampFromFilename()) // day 32
        assertNull("IMG_18000508_213737.jpg".parseTimestampFromFilename()) // year < 1970
    }
}
