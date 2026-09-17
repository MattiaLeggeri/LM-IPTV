package com.example.lmiptv

import android.content.SharedPreferences
import fi.iki.elonen.NanoHTTPD
import java.net.URLEncoder

/** Small local wizard used by the QR code. The phone never needs a cloud account. */
class PhoneGroupServer(
    port: Int,
    private val pin: String,
    private val prefs: SharedPreferences,
    private val saved: (String, String) -> Unit
) : NanoHTTPD(port) {
    private var pendingName = ""
    private var pendingUrl = ""
    private var pendingCategories: Map<String, List<Category>> = emptyMap()

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.parameters["pin"]?.firstOrNull() != pin) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Codice non valido")
            }
            if (session.method == Method.POST) {
                session.parseBody(HashMap())
                val params = session.parameters
                val step = params["step"]?.firstOrNull() ?: "playlist"
                if (step == "groups") return saveGroups(params)
                val name = params["name"]?.firstOrNull()?.trim().orEmpty().ifBlank { "Playlist telefono" }
                val mode = params["mode"]?.firstOrNull() ?: "m3u"
                val url = if (mode == "xtream") {
                    val server = params["server"]?.firstOrNull()?.trim()?.trimEnd('/').orEmpty()
                    val user = URLEncoder.encode(params["username"]?.firstOrNull().orEmpty(), "UTF-8")
                    val pass = URLEncoder.encode(params["password"]?.firstOrNull().orEmpty(), "UTF-8")
                    if (server.isBlank() || user.isBlank() || pass.isBlank()) return bad("Dati Xtream incompleti")
                    "$server/get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
                } else params["m3u"]?.firstOrNull()?.trim().orEmpty()
                if (url.isBlank()) return bad("Inserisci il collegamento della playlist")
                val imported = importSource(url)
                pendingName = name
                pendingUrl = url
                pendingCategories = imported.categories.ifEmpty {
                    imported.items.groupBy { it.kind }.mapValues { entry ->
                        entry.value.map { it.group }.distinct().map { Category(it, it) }
                    }
                }
                groupsPage()
            } else {
                playlistPage()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html; charset=utf-8", page("Errore", "<p>${esc(e.message ?: "Importazione non riuscita")}</p>"))
        }
    }

    private fun saveGroups(params: Map<String, List<String>>): Response {
        val hidden = params["hidden"]?.filter { it.isNotBlank() } ?: emptyList()
        val order = params["order"]?.firstOrNull().orEmpty().split(',').filter { it.isNotBlank() }
        prefs.edit().putStringSet("hidden_groups", hidden.toSet()).putString("group_order", writeStringList(order)).apply()
        saved(pendingName.ifBlank { "Playlist telefono" }, pendingUrl)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page("LM IPTV pronta", "<p>Gruppi salvati. Puoi tornare sull'app: il caricamento si avvia automaticamente.</p>"))
    }

    private fun playlistPage(): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page("Configura LM IPTV", """
        <p>Inserisci la playlist dal telefono. La Fire TV analizzerà i gruppi prima del salvataggio.</p>
        <form method="post" action="/?pin=$pin">
        <input type="hidden" name="step" value="playlist"><input name="name" placeholder="Nome playlist" required>
        <select name="mode" onchange="m3u.hidden=this.value!='m3u';xt.hidden=this.value!='xtream'"><option value="m3u">Link M3U / M3U8</option><option value="xtream">Xtream Codes</option></select>
        <section id="m3u"><input name="m3u" placeholder="https://.../playlist.m3u"></section>
        <section id="xt" hidden><input name="server" placeholder="URL server"><input name="username" placeholder="Username"><input name="password" type="password" placeholder="Password"></section>
        <button type="submit">ANALIZZA PLAYLIST</button></form>
    """))

    private fun groupsPage(): Response {
        val html = StringBuilder("<p>Scegli i gruppi da mostrare. Puoi cambiare categoria e riordinare con i pulsanti.</p><form method='post' action='/?pin=$pin'><input type='hidden' name='step' value='groups'><input type='hidden' id='order' name='order'><div id='groups'>")
        for ((kind, groups) in pendingCategories) {
            html.append("<h2>${esc(label(kind))}</h2><div class='cat' data-kind='$kind'>")
            for (group in groups) {
                val key = "$kind:${group.id}"
                html.append("<label class='row' data-key='${esc(key)}'><input type='checkbox' name='hidden' value='${esc(key)}'> <span>${esc(group.name)}</span><button type='button' onclick='move(this,-1)'>↑</button><button type='button' onclick='move(this,1)'>↓</button></label>")
            }
            html.append("</div>")
        }
        html.append("<button type='submit' onclick='setOrder()'>SALVA E AVVIA SU FIRE TV</button></form><script>function move(b,d){let r=b.parentElement,p=r.parentElement,n=[...p.children],i=n.indexOf(r),j=Math.max(0,Math.min(n.length-1,i+d));if(i!=j)p.insertBefore(r,n[j+(d>0?1:0])||null)}function setOrder(){order.value=[...document.querySelectorAll('.row')].map(x=>x.dataset.key).join(',')}</script>")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page("Scegli i gruppi", html.toString()))
    }

    private fun bad(message: String): Response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/html; charset=utf-8", page("Dati non validi", "<p>${esc(message)}</p><p>Torna indietro e riprova.</p>"))
    private fun label(kind: String) = when (kind) { "LIVE" -> "TV in diretta"; "FILM" -> "Film"; "SERIE" -> "Serie TV"; else -> kind }
    private fun page(title: String, body: String): String = "<meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:system-ui;background:#07101b;color:#fff;padding:22px;max-width:680px;margin:auto}h1{color:#3ee7e1}h2{font-size:18px;color:#9de7ff}.row{display:flex;align-items:center;gap:8px;background:#111d30;border-radius:10px;padding:10px;margin:6px 0}.row span{flex:1}input,select,button{box-sizing:border-box;width:100%;padding:13px;margin:7px 0;border-radius:9px;border:1px solid #34425b;background:#0b1626;color:#fff}button{background:#3ee7e1;color:#06101a;font-weight:800;border:0}.row button{width:42px;padding:8px;margin:0}</style><h1>LM IPTV</h1><h3>$title</h3>$body"
    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
