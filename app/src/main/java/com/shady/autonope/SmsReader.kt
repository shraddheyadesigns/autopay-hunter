package com.shady.autonope

import android.content.Context
import android.provider.Telephony
import app.autopayhunter.engine.Sms
import java.time.Instant
import java.time.ZoneId

/** Reads the SMS inbox: sender, text and time. Nothing else, and nothing is saved. */
object SmsReader {
    fun readInbox(context: Context): List<Sms> {
        val zone = ZoneId.systemDefault()
        val out = ArrayList<Sms>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddress = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val body = c.getString(iBody) ?: continue
                out += Sms(
                    id = c.getLong(iId).toString(),
                    sender = c.getString(iAddress) ?: "",
                    body = body,
                    receivedAt = Instant.ofEpochMilli(c.getLong(iDate)).atZone(zone).toLocalDateTime(),
                )
            }
        }
        return out
    }
}
