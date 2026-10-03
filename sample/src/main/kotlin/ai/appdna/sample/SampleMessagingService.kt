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
        // SDK ingest path: track delivery + render the notification UI.
        // For payloads that aren't AppDNA-issued, host code can branch on
        // message.data["push_id"] or a custom marker before forwarding.
        val pushId = message.data["push_id"]
        if (!pushId.isNullOrBlank()) {
            AppDNA.trackPushDelivered(pushId)
        }
    }
}
