package com.iamode.app.domain

import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.util.LanguageDetector
import com.iamode.app.domain.util.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {
    @Test fun tenglish() = assertEquals(LanguageCode.TE, LanguageDetector.detect("em chesthunav ra").code)
    @Test fun hinglish() = assertEquals(LanguageCode.HI, LanguageDetector.detect("kya kar raha hai bhai").code)
    @Test fun tanglish() = assertEquals(LanguageCode.TA, LanguageDetector.detect("enna panra da").code)
    @Test fun kanglish() = assertEquals(LanguageCode.KN, LanguageDetector.detect("yenu maadtha idiya maga").code)
    @Test fun english() = assertEquals(LanguageCode.EN, LanguageDetector.detect("Can we meet at 5?").code)

    @Test fun teluguScript() {
        val l = LanguageDetector.detect("ఎక్కడ ఉన్నావ్")
        assertEquals(LanguageCode.TE, l.code)
        assertEquals(Script.NATIVE, l.script)
    }

    @Test fun phoneNormalization() {
        assertEquals("919848012345", PhoneNumbers.normalize("+91 98480 12345"))
        assertEquals("919848012345", PhoneNumbers.normalize("098480-12345"))
    }
}
