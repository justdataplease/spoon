package com.justdataplease.spoon.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.BuildConfig
import com.justdataplease.spoon.R
import com.justdataplease.spoon.data.preferences.RecipePublisherOptions

@Composable
internal fun AboutAppButton(modifier: Modifier = Modifier) {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { showAbout = true }, modifier = modifier) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
        Text("Σχετικά", modifier = Modifier.padding(start = 8.dp))
    }
    if (showAbout) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(colorResource(R.color.ic_launcher_background)))
            },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Έκδοση ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
                    Text("Η παρούσα εφαρμογή προορίζεται αποκλειστικά για προσωπική χρήση. Η πνευματική ιδιοκτησία των συνταγών, των φωτογραφιών και του λοιπού περιεχομένου ανήκει στους αντίστοιχους δημιουργούς και δικαιούχους. Κάθε συνταγή παραπέμπει στην αρχική πηγή της.")
                    Text("Πηγές συνταγών", style = MaterialTheme.typography.titleSmall)
                    RecipePublisherOptions.forEach { publisher ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(publisher.websiteUrl) } },
                            modifier = Modifier.fillMaxWidth()) { Text(publisher.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Κλείσιμο") } },
        )
    }
}
