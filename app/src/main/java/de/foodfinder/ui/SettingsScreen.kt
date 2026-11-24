package de.foodfinder.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.foodfinder.data.Service
import de.foodfinder.data.eur
import de.foodfinder.mail.mailPresets
import de.foodfinder.notify.Notifier
import de.foodfinder.ui.theme.LocalAccents
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val p = vm.repo.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val acc = LocalAccents.current
    var tick by remember { mutableIntStateOf(0) } // re-read prefs after changes
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val m = remember(tick) { p.membership }
    val df = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Konten & Einstellungen") })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {

            // ---------------- memberships
            Heading("Mitgliedschaften")
            SwitchRow("Lieferando Plus", m.lieferandoPlus, "Über Amazon Prime · 0 € Lieferung ab ${eur(m.lieferandoPlusMin)} bei teilnehmenden Restaurants\n${m.lieferandoNote}") {
                p.membership = m.copy(lieferandoPlus = it, lieferandoNote = "Manuell gesetzt"); tick++; vm.membershipChanged()
            }
            SwitchRow("Wolt+", m.woltPlusActive(), buildString {
                append("0 € Lieferung ab ${eur(m.woltPlusMin)} bei Wolt+-Partnern")
                m.woltPlusEnds?.let { append("\nEndet am ${df.format(it)}") }
                append("\n${m.woltNote}")
            }) {
                p.membership = m.copy(woltPlus = it, woltPlusEnds = if (it && (m.woltPlusEnds ?: 0) < System.currentTimeMillis()) null else m.woltPlusEnds, woltNote = "Manuell gesetzt"); tick++; vm.membershipChanged()
            }
            var pickDate by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }) { Text(if (m.woltPlusEnds == null) "Wolt+-Enddatum setzen" else "Enddatum ändern") }
                OutlinedButton(onClick = { openApp(ctx, Service.WOLT.packages, "https://wolt.com/de/me") }) { Text("In Wolt prüfen") }
            }
            Text("Der Status wird automatisch aus E-Mails und App-Benachrichtigungen von Wolt und Lieferando aktualisiert (z. B. „Dein Wolt+ endet am …“). Kurz vor Ablauf bekommst du eine Erinnerung, danach rechnet die App ohne Gratis-Lieferung.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (pickDate) {
                val dps = rememberDatePickerState(initialSelectedDateMillis = m.woltPlusEnds ?: System.currentTimeMillis())
                DatePickerDialog(onDismissRequest = { pickDate = false },
                    confirmButton = { TextButton(onClick = {
                        p.membership = m.copy(woltPlusEnds = dps.selectedDateMillis?.plus(23 * 3600_000L), woltNote = "Enddatum manuell gesetzt"); pickDate = false; tick++; vm.membershipChanged()
                    }) { Text("Übernehmen") } },
                    dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Abbrechen") } }) { DatePicker(dps) }
            }

            Divider()
            // ---------------- e-mail
            Heading("Angebote aus E-Mails")
            var gmId by remember { mutableStateOf(p.gmailClientId) }
            var gmSecret by remember { mutableStateOf(p.gmailClientSecret) }
            var mailMsg by remember { mutableStateOf(p.imapLastResult) }
            var scanning by remember { mutableStateOf(false) }
            if (p.gmailConfigured) {
                Text("Verbunden mit ${p.gmailAccount ?: "Google"}", style = MaterialTheme.typography.bodyMedium, color = acc.ok)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { scanning = true; mailMsg = "Postfach wird gelesen…"; vm.scanMail { mailMsg = it; scanning = false; tick++ } }, enabled = !scanning) { Text("Jetzt scannen") }
                    OutlinedButton(onClick = { vm.gmailSignOut(); mailMsg = "Abgemeldet"; tick++ }) { Text("Abmelden") }
                }
            } else {
                Button(onClick = {
                    scanning = true; mailMsg = "Anmeldung im Browser…"
                    vm.gmailSignIn { mailMsg = it; if (!it.endsWith("…")) scanning = false; tick++ }
                }, enabled = !scanning && gmId.isNotBlank() && gmSecret.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Mit Google anmelden (Gmail)") }
            }
            var gmSetup by remember { mutableStateOf(!p.gmailConfigured && (gmId.isBlank() || gmSecret.isBlank())) }
            TextButton(onClick = { gmSetup = !gmSetup }) { Text(if (gmSetup) "Google-Zugang einrichten (einmalig) \u25B2" else "Google-Zugang einrichten (einmalig) \u25BC") }
            if (gmSetup) {
                OutlinedTextField(gmId, { gmId = it.trim(); p.gmailClientId = gmId }, label = { Text("Client-ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(gmSecret, { gmSecret = it.trim(); p.gmailClientSecret = gmSecret }, label = { Text("Client-Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("1) console.cloud.google.com \u2192 neues Projekt anlegen.\n" +
                    "2) \u201EGmail API\u201C aktivieren.\n" +
                    "3) OAuth-Zustimmungsbildschirm: Extern, eigene Adresse als Testnutzer eintragen, danach \u201EApp ver\u00F6ffentlichen\u201C (sonst l\u00E4uft die Anmeldung nach 7 Tagen ab; Google zeigt dann den Hinweis \u201Enicht \u00FCberpr\u00FCfte App\u201C \u2013 \u00FCber \u201EErweitert\u201C fortfahren).\n" +
                    "4) Anmeldedaten \u2192 OAuth-Client-ID erstellen \u2192 Typ \u201EDesktop-App\u201C, Client-ID und Secret hier einf\u00FCgen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { openUrl(ctx, "https://console.cloud.google.com/apis/library/gmail.googleapis.com") }) { Text("Gmail API") }
                    OutlinedButton(onClick = { openUrl(ctx, "https://console.cloud.google.com/apis/credentials") }) { Text("Anmeldedaten") }
                }
            }
            if (mailMsg.isNotBlank() && (p.gmailConfigured || scanning)) Text(mailMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

            Text("Andere Anbieter (IMAP)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            var host by remember { mutableStateOf(p.imapHost) }
            var user by remember { mutableStateOf(p.imapUser) }
            var pwd by remember { mutableStateOf("") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mailPresets.forEach { pr -> FilterChip(host == pr.host && pr.host.isNotEmpty(), { host = pr.host }, label = { Text(pr.label) }) }
            }
            mailPresets.firstOrNull { it.host == host }?.let { Text(it.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(host, { host = it.trim() }, label = { Text("IMAP-Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it.trim() }, label = { Text("E-Mail-Adresse") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(pwd, { pwd = it }, label = { Text(if (p.imapPassword.isNotEmpty()) "App-Passwort (gespeichert, zum Ändern neu eingeben)" else "App-Passwort") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    p.imapHost = host; p.imapUser = user; if (pwd.isNotEmpty()) p.imapPassword = pwd
                    pwd = ""; scanning = true; mailMsg = "Postfach wird gelesen…"
                    vm.scanMail { mailMsg = it; scanning = false; tick++ }
                }, enabled = !scanning && host.isNotBlank() && user.isNotBlank() && (pwd.isNotEmpty() || p.imapPassword.isNotEmpty())) { Text("Speichern & scannen") }
                if (p.imapPassword.isNotEmpty()) TextButton(onClick = { p.imapPassword = ""; p.imapUser = ""; user = ""; mailMsg = "Zugang entfernt"; tick++ }) { Text("Entfernen") }
            }
            if (mailMsg.isNotBlank()) Text(mailMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text("Das Passwort bleibt verschlüsselt auf dem Handy. Gelesen werden nur Mails von Lieferando, Uber Eats, Wolt, Too Good To Go, NeoTaste (und Amazon zu Lieferando) der letzten 3 Wochen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

            val vouchers = remember(tick) { vm.repo.activeVouchers() }
            if (vouchers.isNotEmpty()) {
                Heading("Gefundene Gutscheine (${vouchers.size})")
                vouchers.take(12).forEach { v ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("${v.service.label} · ${v.deal?.label ?: v.title}", style = MaterialTheme.typography.bodyMedium, color = acc.saving)
                        Text(listOfNotNull(if (v.origin.name == "EMAIL") "E-Mail" else "App-Push", v.code?.let { "Code $it" }, v.validUntil?.let { "bis $it" },
                            df.format(v.receivedAt)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Divider()
            // ---------------- notification listener
            Heading("Benachrichtigungen der Liefer-Apps mitlesen")
            val enabled = remember(tick) { Notifier.listenerEnabled(ctx) }
            Text(if (enabled) "Aktiv: Deals aus Pushs von Lieferando, Uber Eats, Wolt, TGTG und NeoTaste werden übernommen." else "Aus. Damit FoodFinder Angebote aus den Pushs der Apps übernimmt, gib den Zugriff frei.",
                style = MaterialTheme.typography.bodyMedium, color = if (enabled) acc.ok else MaterialTheme.colorScheme.onSurface)
            FilledTonalButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (enabled) "Zugriff verwalten" else "Zugriff freigeben")
            }

            Divider()
            // ---------------- TGTG
            Heading("Too Good To Go")
            var tgEmail by remember { mutableStateOf(p.tgtgEmail ?: "") }
            var pin by remember { mutableStateOf("") }
            var tgMsg by remember { mutableStateOf("") }
            var polling by remember { mutableStateOf(p.tgtgPollingId) }
            if (vm.repo.tgtgAuth.loggedIn && tick >= 0) {
                Text("Verbunden als ${p.tgtgEmail ?: "?"}", style = MaterialTheme.typography.bodyMedium, color = acc.ok)
                TextButton(onClick = { vm.repo.tgtgAuth.logout(); tick++ }) { Text("Abmelden") }
            } else {
                OutlinedTextField(tgEmail, { tgEmail = it.trim() }, label = { Text("E-Mail deines TGTG-Kontos") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Button(onClick = {
                    scope.launch {
                        tgMsg = "Anfrage läuft…"
                        runCatching { vm.repo.tgtgAuth.requestLogin(tgEmail) }
                            .onSuccess { polling = it; p.tgtgPollingId = it; tgMsg = "Mail ist unterwegs. Gib die PIN aus der Mail ein – oder öffne den Link im Handy-Browser (nicht in der TGTG-App)." }
                            .onFailure { tgMsg = it.message ?: "Fehler" }
                    }
                }, enabled = tgEmail.contains("@"), modifier = Modifier.padding(top = 8.dp)) { Text("Login-Mail anfordern") }
                if (polling != null) {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN aus der Mail") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            tgMsg = "Prüfe…"
                            var ok = false
                            repeat(if (pin.isBlank()) 6 else 1) {
                                if (!ok) {
                                    ok = runCatching { vm.repo.tgtgAuth.complete(polling!!, pin.ifBlank { null }) }.onFailure { tgMsg = it.message ?: "Fehler" }.getOrDefault(false)
                                    if (!ok && pin.isBlank()) delay(5000)
                                }
                            }
                            if (ok) { tgMsg = "Verbunden"; p.tgtgPollingId = null; polling = null; tick++ } else if (tgMsg == "Prüfe…") tgMsg = "Noch nicht bestätigt. Link geöffnet oder PIN korrekt?"
                        }
                    }, modifier = Modifier.padding(top = 8.dp)) { Text(if (pin.isBlank()) "Ich habe den Link geöffnet" else "PIN bestätigen") }
                }
                if (tgMsg.isNotBlank()) Text(tgMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            Divider()
            // ---------------- notifications
            Heading("Benachrichtigungen")
            var notify by remember { mutableStateOf(p.notifyEnabled) }
            var minSave by remember { mutableStateOf(p.notifyMinSaving.toFloat()) }
            var interval by remember { mutableIntStateOf(p.notifyIntervalMin) }
            var tgNotify by remember { mutableStateOf(p.notifyTgtg) }
            SwitchRow("Neue Top-Angebote melden", notify, "Prüft im Hintergrund am gewählten bzw. zuletzt bekannten Ort") { notify = it; p.notifyEnabled = it }
            Text("Ab einer Ersparnis von ${eur(minSave.toDouble())}", style = MaterialTheme.typography.bodyMedium)
            Slider(minSave, { minSave = (it * 2).toInt() / 2f }, valueRange = 2f..20f, onValueChangeFinished = { p.notifyMinSaving = minSave.toDouble() })
            Text("Prüfen alle", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 240).forEach { mi ->
                    FilterChip(interval == mi, { interval = mi; p.notifyIntervalMin = mi; vm.reschedule() }, label = { Text(if (mi < 60) "$mi Min" else "${mi / 60} Std") })
                }
            }
            SwitchRow("Too Good To Go: neue Tüten in der Nähe", tgNotify) { tgNotify = it; p.notifyTgtg = it }

            Divider()
            Heading("Standard")
            Row { Text("Personen pro Bestellung", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 12.dp)); PersonsStepper(vm.state.value.persons, { vm.setPersons(it); tick++ }) }
            Spacer(Modifier.height(16.dp))
            Text("Daten kommen direkt von den Web-Schnittstellen der Dienste, ohne offizielle API. Ändert ein Dienst etwas, zeigt die Liste die Quelle als nicht erreichbar an. Bestellt wird immer in der App des Dienstes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Heading(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
