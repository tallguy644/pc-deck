package com.desertcircuit.pcdeck

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.math.min

class ConnectActivity : AppCompatActivity() {

    private lateinit var addressInput: TextInputEditText
    private lateinit var tokenInput: TextInputEditText

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val conn = Connection.fromUrl(text)
        if (conn == null) {
            Toast.makeText(this, R.string.bad_qr, Toast.LENGTH_LONG).show()
        } else {
            openDeck(conn)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val saved = Connection.load(this)
        // Jump straight back into the deck on normal launches.
        if (saved != null && !intent.getBooleanExtra(EXTRA_CHOOSE, false) && savedInstanceState == null) {
            openDeck(saved)
            return
        }

        setContentView(R.layout.activity_connect)
        addressInput = findViewById(R.id.addressInput)
        tokenInput = findViewById(R.id.tokenInput)

        // Keep the form a comfortable width on big tablets.
        val card = findViewById<LinearLayout>(R.id.card)
        val dm = resources.displayMetrics
        card.layoutParams = card.layoutParams.apply {
            width = min(dm.widthPixels - (48 * dm.density).toInt(), (460 * dm.density).toInt())
        }

        saved?.let {
            addressInput.setText(if (it.port == Connection.DEFAULT_PORT) it.host else "${it.host}:${it.port}")
            tokenInput.setText(it.token)
        }

        findViewById<MaterialButton>(R.id.scanBtn).setOnClickListener { scan() }
        findViewById<MaterialButton>(R.id.connectBtn).setOnClickListener { connectManual() }
        tokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connectManual(); true } else false
        }
    }

    private fun scan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun connectManual() {
        val conn = Connection.fromManual(
            addressInput.text?.toString().orEmpty(),
            tokenInput.text?.toString().orEmpty()
        )
        if (conn == null) {
            Toast.makeText(this, R.string.need_fields, Toast.LENGTH_SHORT).show()
            return
        }
        openDeck(conn)
    }

    private fun openDeck(conn: Connection) {
        conn.save(this)
        startActivity(Intent(this, DeckActivity::class.java))
        finish()
    }

    companion object {
        private const val EXTRA_CHOOSE = "choose"

        fun chooseIntent(ctx: Context) =
            Intent(ctx, ConnectActivity::class.java).putExtra(EXTRA_CHOOSE, true)
    }
}
