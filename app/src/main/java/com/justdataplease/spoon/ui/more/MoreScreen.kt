package com.justdataplease.spoon.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MoreScreen(
    onOpenCalendar: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAccount: () -> Unit,
    onCreateRecipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(30.dp)) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
                            ),
                        )
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Περισσότερα", style = MaterialTheme.typography.displaySmall)
                    }
                    Text("Το ημερολόγιο, το ιστορικό και ο λογαριασμός σου.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { MoreCard("Ημερολόγιο", "Δες το πλάνο ανά μήνα", Icons.Outlined.CalendarMonth, onOpenCalendar) }
        item { MoreCard("Ιστορικό", "Όλες οι συνταγές που έφτιαξες", Icons.Outlined.History, onOpenHistory) }
        item { MoreCard("Λογαριασμός", "Σύνδεση και συγχρονισμός", Icons.Outlined.AccountCircle, onOpenAccount) }
        item { MoreCard("Νέα δική μου συνταγή", "Υλικά, βήματα και φωτογραφία", Icons.AutoMirrored.Outlined.NoteAdd, onCreateRecipe) }
    }
}

@Composable
private fun MoreCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(21.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(17.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(11.dp).size(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}
