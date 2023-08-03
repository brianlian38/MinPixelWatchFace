/*
 *   Copyright 2021 Benoit LETONDOR
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.benoitletondor.pixelminimalwatchface.settings.phonebattery.troubleshoot

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.benoitletondor.pixelminimalwatchface.R
import kotlinx.android.synthetic.main.activity_phone_battery_troubleshoot.*

class PhoneBatterySyncTroubleshootActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_battery_troubleshoot)

        wearable_recycler_view.isEdgeItemsCenteringEnabled = true
        wearable_recycler_view.layoutManager = LinearLayoutManager(this)
        wearable_recycler_view.setHasFixedSize(true)
        wearable_recycler_view.adapter = ContentAdapter()
    }
}

private class ContentAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.phone_battery_sync_troubleshoot_title, parent, false)) {}
        }

        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.phone_battery_sync_troubleshoot, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}

    override fun getItemCount(): Int = 2

    override fun getItemViewType(position: Int): Int = position

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}