package ai.appdna.sample.widget

import ai.appdna.sample.R
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/*
 * Synthetic home-screen widgets for walking the onboarding "Add widget" button action.
 *
 * The SDK ships no widget: it pins the HOST's own provider. A stock sample build declares none of
 * these, so it behaves as an app with no widget. A walk build declares one or both in its manifest
 * (`-PappdnaSampleWidgets=one|two`, see sample/build.gradle.kts):
 *   one → StreakWidget only;  two → StreakWidget and TasksWidget (sorted: StreakWidget comes first).
 */
abstract class SampleWidget(private val label: String) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.sample_widget)
            views.setTextViewText(R.id.sample_widget_label, label)
            manager.updateAppWidget(id, views)
        }
    }
}

class StreakWidget : SampleWidget("Sample streak: 3 days")

class TasksWidget : SampleWidget("Sample tasks: 2 left")
