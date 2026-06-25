package com.Rouxin.ShellTool

import android.content.Context
import android.util.Base64
import java.io.File

object RxinSandbox {

    private var initialized = false


    fun getPathFragment(context: Context): String {
        return getHome(context) + "/bin"
    }

    fun getHome(context: Context): String {
        return "/data/data/com.Rouxin.ShellTool.mp/RXin"
    }

    fun getShinitPath(context: Context): String {
        return "/data/data/com.Rouxin.ShellTool.mp/RXin/.shinit"
    }

    fun createSandbox(context: Context, useRoot: Boolean) {
        if (initialized) return
        initialized = true

        val home = getHome(context)
        val binDir = "$home/bin"
        val shinitPath = "$home/.shinit"

        createViaFileApi(home, binDir, shinitPath)

        // Also try /data/local/tmp if root
        if (useRoot) {
            linkToDataLocalTmp(home, binDir)
        }
    }

    private fun createViaFileApi(home: String, binDir: String, shinitPath: String) {
        File(binDir).mkdirs()
        File("$home/lib").mkdirs()
        File("$home/etc").mkdirs()
        File("$home/share").mkdirs()

        decodeToFile(File(binDir), "engine", SCRIPT__ENGINE)
        decodeToFile(File(binDir), "fortune", SCRIPT__FORTUNE)
        decodeToFile(File(binDir), "hello", SCRIPT__HELLO)
        decodeToFile(File(binDir), "nyan", SCRIPT__NYAN)
        decodeToFile(File(binDir), "rr", SCRIPT__RR)
        decodeToFile(File(binDir), "rxe", SCRIPT__RXE)
        decodeToFile(File(binDir), "snow", SCRIPT__SNOW)

        decodeToFile(File(home), ".shinit", SCRIPT__SHINIT)

        File(binDir).listFiles()?.forEach { file ->
            if (file.isFile) file.setExecutable(true, false)
        }
    }

    private fun linkToDataLocalTmp(home: String, binDir: String) {
        val tmpHome = "/data/local/tmp/RXin"
        try {
            val proc = Runtime.getRuntime().exec("su")
            val os = proc.outputStream
            os.write(("cp -r " + home + " " + tmpHome + "\n").toByteArray(Charsets.UTF_8))
            os.write("exit\n".toByteArray(Charsets.UTF_8))
            os.flush()
            proc.waitFor()
        } catch (e: Exception) {
            // Fallback to app data dir
        }
    }

    private fun decodeToFile(dir: File, name: String, base64: String) {
        val file = File(dir, name)
        val data = Base64.decode(base64, Base64.DEFAULT)
        val content = String(data, Charsets.UTF_8)
        if (file.exists() && file.readText() == content) return
        file.writeText(content)
    }


    private val SCRIPT__ENGINE = "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzM2bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tK1wwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNm18ICAgICAgICAgUlhpbiBFbmdpbmUgU3RhdHVzIFJlcG9ydCAgICAgICB8XDAzM1swbVxuJwpwcmludGYgJyAgXDAzM1sxOzM2bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tK1wwMzNbMG1cbicKcHJpbnRmICdcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIFtPS11cMDMzWzBtICBTYW5kYm94IGluaXRpYWxpemVkXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICBbT0tdXDAzM1swbSAgQmluYXJ5IG1vZHVsZXMgbG9hZGVkXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICBbT0tdXDAzM1swbSAgUEFUSCBpbnRlZ3JhdGlvbiBhY3RpdmVcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIFtPS11cMDMzWzBtICBSb290IHNoZWxsIHJlbGF5IG9ubGluZVxuJwpwcmludGYgJyAgXDAzM1sxOzMzbSAgWy0tXVwwMzNbMG0gIE5ldXJhbCBpbnRlcmZhY2U6IG9mZmxpbmVcbicKcHJpbnRmICcgIFwwMzNbMTszM20gIFstLV1cMDMzWzBtICBRdWFudHVtIHByb2Nlc3Nvcjogb2ZmbGluZVxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sybSAgU3lzdGVtIHJlYWR5LlwwMzNbMG1cbicKcHJpbnRmICdcbicK"
    private val SCRIPT__FORTUNE = "IyEvc3lzdGVtL2Jpbi9zaApjYXNlICQoKCAkJCAlIDYgKyAxICkpIGluCiAgMSkgcHJpbnRmICdcbiAgXDAzM1sxOzMzbSJEZWJ1Z2dpbmcgaXMgdHdpY2UgYXMgaGFyZCBhcyB3cml0aW5nIHRoZSBjb2RlLiJcMDMzWzBtXG4gIFwwMzNbMm0tIEJyaWFuIEtlcm5pZ2hhblwwMzNbMG1cblxuJyA7OwogIDIpIHByaW50ZiAnXG4gIFwwMzNbMTszM20iRmlyc3QsIHNvbHZlIHRoZSBwcm9ibGVtLiBUaGVuLCB3cml0ZSB0aGUgY29kZS4iXDAzM1swbVxuICBcMDMzWzJtLSBKb2huIEpvaG5zb25cMDMzWzBtXG5cbicgOzsKICAzKSBwcmludGYgJ1xuICBcMDMzWzE7MzNtIkl0IHdvcmtzIG9uIG15IG1hY2hpbmUuIlwwMzNbMG1cbiAgXDAzM1sybS0gRXZlcnkgRGV2ZWxvcGVyIEV2ZXJcMDMzWzBtXG5cbicgOzsKICA0KSBwcmludGYgJ1xuICBcMDMzWzE7MzNtIkNhY2hlIGludmFsaWRhdGlvbiBhbmQgbmFtaW5nIHRoaW5ncy4iXDAzM1swbVxuICBcMDMzWzJtLSBQaGlsIEthcmx0b25cMDMzWzBtXG5cbicgOzsKICA1KSBwcmludGYgJ1xuICBcMDMzWzE7MzVtInN1ZG8gISEiXDAzM1swbVxuICBcMDMzWzJtLSBUaGF0IG9uZSBmaXhcMDMzWzBtXG5cbicgOzsKICA2KSBwcmludGYgJ1xuICBcMDMzWzE7MzZtIlRlc3QgaW4gcHJvZHVjdGlvbiB0aGV5IHNhaWQuIlwwMzNbMG1cbiAgXDAzM1sybS0gRGV2T3BzXDAzM1swbVxuXG4nIDs7CmVzYWMK"
    private val SCRIPT__HELLO = "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzM1bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tK1wwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNW18ICAgICAgICAgICAgICAgICAgICAgICAgICAgIHxcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzVtfCAgIFwwMzNbMTszM21XZWxjb21lIHRvIFJYaW4hXDAzM1sxOzM1bSAgICAgICAgfFwwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNW18ICAgICAgICAgICAgICAgICAgICAgICAgICAgIHxcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzVtKy0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0rXDAzM1swbVxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sybVR5cGUgcnhlIHRvIGV4cGxvcmUuXDAzM1swbVxuJwpwcmludGYgJ1xuJwo="
    private val SCRIPT__NYAN = "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xyICBcMDMzWzM1bSAgX19fXDAzM1swbSAgICAgJwpzbGVlcCAwLjEKcHJpbnRmICdcciAgXDAzM1szM20gIHx8X3x8XDAzM1swbSAgICAnCnNsZWVwIDAuMQpwcmludGYgJ1xyICBcMDMzWzMybSAgfCggKXxcMDMzWzBtICAgICcKc2xlZXAgMC4xCnByaW50ZiAnXHIgIFwwMzNbMzZtIF98IHx8IHxfXDAzM1swbSAgJwpzbGVlcCAwLjEKcHJpbnRmICdcciAgXDAzM1szNG18LCAgLiciJyInICAsfFwwMzNbMG0gJwpzbGVlcCAwLjEKcHJpbnRmICdcciAgXDAzM1szNW18ICAnIiciJy0tJyInIicgIHxcMDMzWzBtICcKc2xlZXAgMC4xCnByaW50ZiAnXHIgIFwwMzNbMzFtICciJyInLnx8Xy4nIiciJyBcMDMzWzBtICcKc2xlZXAgMC4yCnByaW50ZiAnXG4gIFwwMzNbMzVtTnlhbiFcMDMzWzBtXG4nCg=="
    private val SCRIPT__RR = "IyEvc3lzdGVtL2Jpbi9zaApDT0xTPSQoc3R0eSBzaXplIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDJ9JykKWyAteiAiJENPTFMiIF0gJiYgQ09MUz00MApbICIkQ09MUyIgLWx0IDEwIF0gJiYgQ09MUz00MApURVhUPScgIFJYaW4gU2hlbGxUb29sQm94ICAnCmZvciBjIGluIDMxIDMzIDMyIDM2IDM0IDM1OyBkbwogIExFTj0keyNURVhUfQogIFBBRD0kKCggKENPTFMgLSBMRU4pIC8gMiApKQogIFsgIiRQQUQiIC1sdCAwIF0gJiYgUEFEPTAKICBTUEFDRVM9JycKICBqPTAKICB3aGlsZSBbICRqIC1sdCAkUEFEIF07IGRvCiAgICBTUEFDRVM9IiRTUEFDRVMgIgogICAgaj0kKCggaiArIDEgKSkKICBkb25lCiAgcHJpbnRmICdcMDMzWyVkbSVzJXNcMDMzWzBtXG4nICIkYyIgIiRTUEFDRVMiICIkVEVYVCIKICBzbGVlcCAwLjE1CmRvbmUK"
    private val SCRIPT__RXE = "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzM2bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLStcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzZtfCAgICAgICAgIFJYaW4gRW5naW5lIHYxLjAgICAgICAgICAgICAgfFwwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNm18ICAgICAgIFByaXZhdGUgU2FuZGJveCBTeXN0ZW0gICAgICAgICAgfFwwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNm0rLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0rXDAzM1swbVxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzMzbUF2YWlsYWJsZSBDb21tYW5kczpcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICBoZWxsb1wwMzNbMG0gICAgLSBXZWxjb21lIG1lc3NhZ2VcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIG55YW5cMDMzWzBtICAgICAtIE55YW4gY2F0IG1pbmlcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIGZvcnR1bmVcMDMzWzBtICAtIFJhbmRvbSBxdW90ZVxuJwpwcmludGYgJyAgXDAzM1sxOzMybSAgZW5naW5lXDAzM1swbSAgIC0gU3lzdGVtIHN0YXR1c1xuJwpwcmludGYgJyAgXDAzM1sxOzMybSAgcnhlXDAzM1swbSAgICAgIC0gVGhpcyBoZWxwIG1lbnVcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIHNub3dcMDMzWzBtICAgICAtIFRlcm1pbmFsIHNub3dmYWxsXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICByclwwMzNbMG0gICAgICAgLSBSYWluYm93IHNjcm9sbFxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sybVR5cGUgYW55IGNvbW1hbmQgdG8gZXhwbG9yZS5cMDMzWzBtXG4nCnByaW50ZiAnXG4nCg=="
    private val SCRIPT__SNOW = "IyEvc3lzdGVtL2Jpbi9zaApXPSQoc3R0eSBzaXplIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDJ9JykKWyAteiAiJFciIF0gJiYgVz02MApbICIkVyIgLWx0IDEwIF0gJiYgVz02MAp0cmFwICd0cHV0IGNub3JtOyBleGl0JyBJTlQgVEVSTQp0cHV0IGNpdmlzIDI+L2Rldi9udWxsCmk9MAp3aGlsZSBbICRpIC1sdCAzMCBdOyBkbwogIFg9JCgoIChpICogNyArICQkKSAlIFcgKSkKICBSPSQoKCBpICUgMTAgKyAxICkpCiAgcHJpbnRmICdcMDMzWyVkOyVkSFwwMzNbMTszN20qXDAzM1swbScgIiRSIiAiJFgiCiAgaT0kKCggaSArIDEgKSkKZG9uZQpwcmludGYgJ1wwMzNbMTE7MUhcMDMzWzJtTWVycnkgUlhpbiFcMDMzWzBtJwp0cHV0IGNub3JtIDI+L2Rldi9udWxsCnByaW50ZiAnXG4nCg=="
    private val SCRIPT__SHINIT = "ZW5naW5lKCkgeyBjb21tYW5kIHNoICcvZGF0YS9kYXRhL2NvbS5Sb3V4aW4uU2hlbGxUb29sLm1wL1JYaW4vYmluL2VuZ2luZScgXCIkQFwiOyB9CmZvcnR1bmUoKSB7IGNvbW1hbmQgc2ggJy9kYXRhL2RhdGEvY29tLlJvdXhpbi5TaGVsbFRvb2wubXAvUlhpbi9iaW4vZm9ydHVuZScgXCIkQFwiOyB9CmhlbGxvKCkgeyBjb21tYW5kIHNoICcvZGF0YS9kYXRhL2NvbS5Sb3V4aW4uU2hlbGxUb29sLm1wL1JYaW4vYmluL2hlbGxvJyBcIiRAXCI7IH0KbnlhbigpIHsgY29tbWFuZCBzaCAnL2RhdGEvZGF0YS9jb20uUm91eGluLlNoZWxsVG9vbC5tcC9SWGluL2Jpbi9ueWFuJyBcIiRAXCI7IH0KcnIoKSB7IGNvbW1hbmQgc2ggJy9kYXRhL2RhdGEvY29tLlJvdXhpbi5TaGVsbFRvb2wubXAvUlhpbi9iaW4vcnInIFwiJEBcIjsgfQpyeGUoKSB7IGNvbW1hbmQgc2ggJy9kYXRhL2RhdGEvY29tLlJvdXhpbi5TaGVsbFRvb2wubXAvUlhpbi9iaW4vcnhlJyBcIiRAXCI7IH0Kc25vdygpIHsgY29tbWFuZCBzaCAnL2RhdGEvZGF0YS9jb20uUm91eGluLlNoZWxsVG9vbC5tcC9SWGluL2Jpbi9zbm93JyBcIiRAXCI7IH0K"
}