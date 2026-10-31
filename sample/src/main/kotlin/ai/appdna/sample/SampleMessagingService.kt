// sample/example app.
//
// Minimal Firebase Cloud Messaging service that hands every push token and
// every incoming message off to the AppDNA SDK. Hosts that already extend
// FirebaseMessagingService for other reasons can use this as a template;
// hosts that have no other push needs can just extend
// `ai.appdna.sdk.integrations.AppDNAMessagingService` instead and skip the
// boilerplate entirely.
package ai.appdna.sample

import ai.appdna.sdk.AppDNA
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SampleMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Forward FCM-issued tokens to AppDNA so push targeting works.
        // setPushToken is the public, host-facing API; onNewPushToken is the
        // SDK's internal handler — either is acceptable.
        AppDNA.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Forward the whole message: for an AppDNA push the SDK tracks delivery and posts the
        // notification; for any other message it returns false and does nothing. Do not also call
        // trackPushDelivered here, or the delivery counts twice.
        if (AppDNA.push.handleMessage(message)) return
        // ... your own handling of non-AppDNA messages
    }
}
