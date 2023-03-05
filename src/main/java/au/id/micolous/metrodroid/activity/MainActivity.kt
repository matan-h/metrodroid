/*
 * MainActivity.kt
 *
 * Copyright 2011-2015 Eric Butler <eric@codebutler.com>
 * Copyright 2015-2019 Michael Farrell <micolous+git@gmail.com>
 * Copyright 2018-2019 Google
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package au.id.micolous.metrodroid.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.tech.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import au.id.micolous.farebot.R
import au.id.micolous.metrodroid.MetrodroidApplication
import au.id.micolous.metrodroid.multi.Localizer
import au.id.micolous.metrodroid.util.Preferences
import au.id.micolous.metrodroid.util.Utils
import au.id.micolous.metrodroid.util.ifTrue
import com.felhr.usbserial.UsbSerialDevice
import com.google.gson.Gson

class MainActivity : MetrodroidActivity() {
    private var mNfcAdapter: NfcAdapter? = null
    private var mPendingIntent: PendingIntent? = null
    private var screen: UsbDevice? = null;
    private val mTechLists = arrayOf(
            arrayOf(IsoDep::class.java.name),
            arrayOf(MifareClassic::class.java.name),
            arrayOf(MifareUltralight::class.java.name),
            arrayOf(NfcA::class.java.name),
            arrayOf(NfcF::class.java.name),
            arrayOf(NfcV::class.java.name))
    private val forceClaim = true
    private val TIMEOUT = 0

    override val themeVariant get(): Int? = R.attr.MainActivityTheme


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main)

        setHomeButtonEnabled(false)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (mNfcAdapter != null) {
            Utils.checkNfcEnabled(this, mNfcAdapter)

            val intent = Intent(this, ReadingTagActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE
                else
                    0
            mPendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)
        }

        updateObfuscationNotice(mNfcAdapter != null)
        findViewById<Button>(R.id.history_button).setOnClickListener {
            onHistoryClick(it)
        }
        findViewById<Button>(R.id.otg_button).setOnClickListener {
            setupOtg()
        }
        findViewById<Button>(R.id.supported_cards_button).setOnClickListener {
            onSupportedCardsClick(it)
        }
        //Creating a shared preference

        screen= intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        val sharedPref = this@MainActivity.getSharedPreferences(
            "prefs", Context.MODE_PRIVATE)
        with (sharedPref.edit()){
            val gson = Gson();
            val json = gson.toJson(screen)
            putString("SerializableDevice", json)
//            sharedPref.commit()
            apply()
        }

        setupOtg()


    }

    fun setupOtg() {



        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = manager.deviceList
        if (deviceList.isEmpty()){
            Log.d("sendOtg","no device")
            return
        }
        var deviceD = deviceList[deviceList.keys.first()]


        //val sharedPref = this@CardInfoActivity.getSharedPreferences(
        //   "prefs", Context.MODE_PRIVATE)
        //val deviceJson = sharedPref.getString("SerializableDevice","").orEmpty();
        //val gson = Gson();
        //val deviceD = gson.fromJson(deviceJson,UsbDevice::class.java);
        //showDialog(this@CardInfoActivity, "sendOtg:deviceList", deviceD.toString());

        Log.d("sendOtg", deviceD.toString())
        var usbConnection = manager.openDevice(deviceD);

        var usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(deviceD,usbConnection);
        usbSerialDevice.setBaudRate(9600)
        Log.d("sendOtg", "created device")
        usbSerialDevice.open();
        (this.application as MetrodroidApplication).usbSerialDevice = usbSerialDevice
//        Log.d("sendOtg", "opened device")
//        Handler(Looper.getMainLooper()).postDelayed({
//            usbSerialDevice.write("test2".toByteArray())
//            Log.d("sendOtg", "write device")
//            //usbSerialDevice.close()
//        }, 5000)



//        deviceD?.getInterface(0)?.also { intf ->
//            intf.getEndpoint(0)?.also { endpoint ->
//                manager.openDevice(deviceD)?.apply {
//                    claimInterface(intf, forceClaim)
//                    Log.d("sendOtg", "after claim interface")
//                    bulkTransfer(
//                        endpoint,
//                        data.toByteArray(),
//                        data.length,
//                        TIMEOUT
//                    ) //do in another thread
//                    Log.d("sendOtg", "sent data")
//                }
//            }
//        }
    }

    override fun onResume() {
        super.onResume()

        updateObfuscationNotice(mNfcAdapter != null)
        mNfcAdapter?.enableForegroundDispatch(this, mPendingIntent, null, mTechLists)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateObfuscationNotice(mNfcAdapter != null)
        }
    }

    private fun updateObfuscationNotice(hasNfc: Boolean) {
        val obfuscationFlagsOn = (if (Preferences.hideCardNumbers) 1 else 0) +
                (if (Preferences.obfuscateBalance) 1 else 0) +
                (if (Preferences.obfuscateTripDates) 1 else 0) +
                (if (Preferences.obfuscateTripFares) 1 else 0) +
                if (Preferences.obfuscateTripTimes) 1 else 0

        val directions = findViewById<TextView>(R.id.directions)
        val felicaNote = Preferences.felicaOnlyFirst.ifTrue { R.string.felica_first_system_notice }

        if (obfuscationFlagsOn > 0) {
            val flagsNote = Localizer.localizePlural(
                R.plurals.obfuscation_mode_notice, obfuscationFlagsOn, obfuscationFlagsOn)
            directions.text = felicaNote?.let {
                "${Localizer.localizeString(it)} $flagsNote" } ?: flagsNote
        } else if (felicaNote != null) {
            directions.setText(felicaNote)
        } else if (!hasNfc) {
            directions.setText(R.string.nfc_unavailable)
        } else {
            directions.setText(R.string.directions)
        }
    }

    override fun onPause() {
        super.onPause()
        mNfcAdapter?.disableForegroundDispatch(this)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onSupportedCardsClick(view: View) {
        startActivity(Intent(this, SupportedCardsActivity::class.java))
    }

    @Suppress("UNUSED_PARAMETER")
    fun onHistoryClick(view: View) {
        startActivity(Intent(this, CardsActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.prefs -> startActivity(Intent(this, PreferencesActivity::class.java))
            R.id.keys -> startActivity(Intent(this, KeysActivity::class.java))
        }

        return false
    }
}
