package vn.softworld.readingrobot.engine

import org.junit.Assert.assertNull
import org.junit.Test

class EngineParityTest {
    @Test fun kotlinEngineMatchesPython() {
        val json = javaClass.classLoader!!.getResource("parity.json")!!.readText()
        assertNull(ParityRunner.run(json))
    }

    @Test fun wordCountIsComputed() {
        val p = Passage.build("m7", "The Lost Sock", "Milo the cat had a big problem. His favourite red sock was gone!")
        org.junit.Assert.assertEquals(13, p.wordCount)
        org.junit.Assert.assertEquals(2, p.sentences.size)
    }
}
