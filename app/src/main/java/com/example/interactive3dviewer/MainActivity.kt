package com.example.interactive3dviewer

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var stage: StageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        stage = findViewById(R.id.stage)
        findViewById<Button>(R.id.btnAddModel).setOnClickListener { showModelMenu(it) }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun showModelMenu(anchor: View) {
        val files = assets.list("models")?.filter { it.endsWith(".glb", ignoreCase = true) }?.sorted().orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "No .glb files in assets/models/", Toast.LENGTH_LONG).show()
            return
        }
        PopupMenu(this, anchor).apply {
            files.forEachIndexed { i, f -> menu.add(Menu.NONE, i, i, f.substringBeforeLast('.').replace('_', ' ')) }
            setOnMenuItemClickListener { stage.addModel(files[it.itemId]); true }
        }.show()
    }

    override fun onResume() { super.onResume(); stage.onResume() }
    override fun onPause() { stage.onPause(); super.onPause() }
    override fun onDestroy() { stage.release(); super.onDestroy() }
}