package io.github.oceansde012.dotline

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** The widget opens this section for full hours and the shared group setting. */
internal class OutagePanel(
    private val activity: Activity,
    ui: Ui,
    private val onChanged: () -> Unit,
) {
    val view = ui.vertical().apply {
        setBackgroundResource(R.drawable.nd_tile_r22)
        val pad = ui.dp(16)
        setPadding(pad, pad, pad, pad)
    }

    private val group = ui.button("", primary = false).apply { setOnClickListener { chooseGroup() } }
    private val tomorrow = ui.label("", Nd.DIM, 11f)
    private val tomorrowHours = ui.body("", Nd.FG, 15f)
    private val tomorrowChecked = ui.body("", Nd.DIM, 11f)
    private val today = ui.label("", Nd.DIM, 11f)
    private val todayHours = ui.body("", Nd.FG, 14f)
    private val todayChecked = ui.body("", Nd.DIM, 11f)
    private val refresh = ui.button(activity.getString(R.string.outage_refresh_button), primary = false).apply {
        setOnClickListener { refresh(force = true) }
    }

    init {
        view.addView(ui.label(activity.getString(R.string.outage_panel_title), Nd.FG, 12f))
        view.addView(group, ui.lp(top = 12))
        view.addView(tomorrow, ui.lp(top = 18))
        view.addView(tomorrowHours, ui.lp(top = 4))
        view.addView(tomorrowChecked, ui.lp(top = 4))
        view.addView(today, ui.lp(top = 14))
        view.addView(todayHours, ui.lp(top = 4))
        view.addView(todayChecked, ui.lp(top = 4))
        view.addView(refresh, ui.lp(top = 12))
        view.addView(ui.link(activity.getString(R.string.outage_find_queue)).apply {
            setOnClickListener { open(OutageRepo.FIND_QUEUE) }
        })
        view.addView(ui.link(activity.getString(R.string.outage_source), Nd.DIM).apply {
            setOnClickListener { open(OutageRepo.SOURCE) }
        })
        render()
    }

    fun refresh(force: Boolean = false) {
        render()
        if (OutageRepo.queue(activity) == null) return
        refresh.isEnabled = false
        OutageRepo.refresh(activity, force) {
            if (!activity.isDestroyed && !activity.isFinishing) {
                render()
                onChanged()
            }
        }
    }

    private fun chooseGroup() {
        val queues = OutageSchedule.queues
        AlertDialog.Builder(activity)
            .setTitle(R.string.outage_choose)
            .setSingleChoiceItems(queues.toTypedArray(), queues.indexOf(OutageRepo.queue(activity))) { dialog, index ->
                OutageRepo.chooseQueue(activity, queues[index])
                dialog.dismiss()
                OutageWidgetProvider.reschedule(activity)
                onChanged()
                refresh()
            }
            .show()
    }

    private fun render() {
        val queue = OutageRepo.queue(activity)
        group.text = if (queue == null) activity.getString(R.string.outage_choose_button)
        else activity.getString(R.string.outage_queue, queue)
        refresh.isEnabled = queue != null
        val date = OutageSchedule.date()
        val next = date.plusDays(1)
        tomorrow.text = activity.getString(R.string.outage_title_date, OutageWidgetProvider.dateLabel(next))
        today.text = activity.getString(R.string.outage_today_date, OutageWidgetProvider.dateLabel(date))
        val nextDay = queue?.let { OutageRepo.day(activity, next, it) }
        val thisDay = queue?.let { OutageRepo.day(activity, date, it) }
        tomorrowHours.text = hours(nextDay)
        todayHours.text = hours(thisDay)
        tomorrowChecked.text = checked(nextDay)
        todayChecked.text = checked(thisDay)
        tomorrowChecked.visibility = if (tomorrowChecked.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        todayChecked.visibility = if (todayChecked.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun hours(day: OutageRepo.Day?): String =
        if (day == null) activity.getString(R.string.outage_choose) else OutageWidgetProvider.status(activity, day)

    private fun checked(day: OutageRepo.Day?): String =
        if (day != null && day.checkedAt > 0 && (day.windows != null || !day.stale)) OutageWidgetProvider.checkedLabel(activity, day)
        else ""

    private fun open(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.outage_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
