package br.com.nexotools.domain

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BidirectionalSourceTest {
    @Test fun sourcesContainNoInvisibleBidirectionalControls() {
        val root=File("src")
        assertTrue("Diretório de fontes não encontrado",root.isDirectory)
        val unsafe=mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt","java","xml","kts") }.forEach { file ->
            if(file.readText(Charsets.UTF_8).any { char ->
                char.code in 0x202A..0x202E || char.code in 0x2066..0x2069 ||
                    char.code in setOf(0x200E,0x200F,0x061C)
            }) unsafe.add(file.relativeTo(root).path)
        }
        assertTrue("Controle bidirecional invisível em: ${unsafe.joinToString()}",unsafe.isEmpty())
    }
}
