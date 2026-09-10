package com.justdataplease.spoon.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.justdataplease.spoon.domain.repository.PersonalSyncState

@Composable
fun AccountScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    onCreateAccount: (email: String, password: String) -> Unit,
    onResetPassword: (email: String) -> Unit,
    onSignOut: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var mode by rememberSaveable(state.dataOwnerKey) { mutableStateOf(AccountFormMode.SIGN_IN) }
    var email by rememberSaveable(state.email) { mutableStateOf(state.email) }
    // Passwords deliberately stay out of Android's persisted saved-state Bundle.
    var password by remember(state.dataOwnerKey) { mutableStateOf("") }
    var passwordConfirmation by remember(state.dataOwnerKey) { mutableStateOf("") }
    var localError by remember(state.dataOwnerKey) { mutableStateOf<String?>(null) }

    fun switchMode(value: AccountFormMode) {
        if (value == mode) return
        mode = value
        password = ""
        passwordConfirmation = ""
        localError = null
        onClearError()
    }

    fun edit(apply: () -> Unit) {
        apply()
        localError = null
        onClearError()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Πίσω")
                }
                Column {
                    Text("Λογαριασμός", style = MaterialTheme.typography.headlineMedium)
                    Text("Τα δεδομένα σου και ο συγχρονισμός τους", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            PersonalSyncStatusCard(state.syncState)
        }

        if (state.isSignedIn && !state.isAnonymous) {
            item {
                SignedInAccountCard(state = state, onSignOut = onSignOut)
            }
        } else {
            item {
                GuestStatusCard()
            }
            item {
                AccountFormCard(
                    mode = mode,
                    email = email,
                    password = password,
                    passwordConfirmation = passwordConfirmation,
                    isBusy = state.isBusy,
                    onEmailChange = { value -> edit { email = value } },
                    onPasswordChange = { value -> edit { password = value } },
                    onPasswordConfirmationChange = { value -> edit { passwordConfirmation = value } },
                    onModeChange = ::switchMode,
                    onSubmit = {
                        val error = validateAccountInput(mode, email, password, passwordConfirmation)
                        if (error != null) {
                            localError = error
                        } else when (mode) {
                            AccountFormMode.SIGN_IN -> onSignIn(email.trim(), password)
                            AccountFormMode.SIGN_UP -> onCreateAccount(email.trim(), password)
                            AccountFormMode.RESET -> onResetPassword(email.trim())
                        }
                    },
                    onForgotPassword = { switchMode(AccountFormMode.RESET) },
                    onCancelReset = { switchMode(AccountFormMode.SIGN_IN) },
                )
            }
        }
        val error = localError ?: state.errorMessage
        if (!error.isNullOrBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(error, modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun PersonalSyncStatusCard(state: PersonalSyncState) {
    val status = personalSyncPresentation(state)
    val icon = when (status.indicator) {
        PersonalSyncIndicator.DEVICE -> Icons.Outlined.PhoneAndroid
        PersonalSyncIndicator.UPLOADING -> Icons.Outlined.Sync
        PersonalSyncIndicator.SYNCED -> Icons.Outlined.CloudDone
        PersonalSyncIndicator.WAITING -> Icons.Outlined.Schedule
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(status.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GuestStatusCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.padding(12.dp).size(26.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Χρήση χωρίς λογαριασμό",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Όλες οι λειτουργίες δουλεύουν στη συσκευή σου χωρίς λογαριασμό. Αν θέλεις αντίγραφο ασφαλείας, δημιούργησε λογαριασμό ή συνδέσου. Τα υπάρχοντα δεδομένα και όλο το ιστορικό σου προστίθενται αυτόματα στον λογαριασμό και συγχρονίζονται όταν υπάρχει σύνδεση.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SignedInAccountCard(state: AccountUiState, onSignOut: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.padding(18.dp).size(38.dp))
            }
            Text(state.email, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(
                    if (state.isEmailVerified == true) Icons.Outlined.CheckCircle else Icons.Outlined.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    when (state.isEmailVerified) {
                        true -> "Επιβεβαιωμένη ηλεκτρονική διεύθυνση"
                        false -> "Η ηλεκτρονική διεύθυνση δεν έχει επιβεβαιωθεί"
                        null -> "Συνδεδεμένος λογαριασμός"
                    },
                    modifier = Modifier.padding(start = 7.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onSignOut, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                Text("Αποσύνδεση", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun AccountFormCard(
    mode: AccountFormMode,
    email: String,
    password: String,
    passwordConfirmation: String,
    isBusy: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmationChange: (String) -> Unit,
    onModeChange: (AccountFormMode) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onCancelReset: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode != AccountFormMode.RESET) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = mode == AccountFormMode.SIGN_IN,
                        onClick = { onModeChange(AccountFormMode.SIGN_IN) },
                        enabled = !isBusy,
                        label = { Text("Σύνδεση") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = mode == AccountFormMode.SIGN_UP,
                        onClick = { onModeChange(AccountFormMode.SIGN_UP) },
                        enabled = !isBusy,
                        label = { Text("Εγγραφή") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                when (mode) {
                    AccountFormMode.SIGN_IN -> "Σύνδεση σε υπάρχοντα λογαριασμό"
                    AccountFormMode.SIGN_UP -> "Νέος λογαριασμός"
                    AccountFormMode.RESET -> "Επαναφορά κωδικού"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (mode == AccountFormMode.SIGN_UP) {
                Text(
                    "Η εγγραφή χρειάζεται σύνδεση στο διαδίκτυο. Τα δεδομένα σου παραμένουν διαθέσιμα στη συσκευή.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ηλεκτρονική διεύθυνση") },
                leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            if (mode != AccountFormMode.RESET) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Κωδικός") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            if (mode == AccountFormMode.SIGN_UP) {
                OutlinedTextField(
                    value = passwordConfirmation,
                    onValueChange = onPasswordConfirmationChange,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Επιβεβαίωση κωδικού") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            Button(onClick = onSubmit, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    when (mode) {
                        AccountFormMode.RESET -> Icons.Outlined.RestartAlt
                        AccountFormMode.SIGN_UP -> Icons.Outlined.PersonAdd
                        AccountFormMode.SIGN_IN -> Icons.Outlined.Person
                    },
                    contentDescription = null,
                )
                Text(
                    when {
                        isBusy -> "Παρακαλώ περίμενε…"
                        mode == AccountFormMode.SIGN_IN -> "Σύνδεση"
                        mode == AccountFormMode.SIGN_UP -> "Δημιουργία λογαριασμού"
                        else -> "Αποστολή μηνύματος επαναφοράς"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (mode == AccountFormMode.SIGN_IN) {
                TextButton(onClick = onForgotPassword, enabled = !isBusy, modifier = Modifier.align(Alignment.End)) {
                    Text("Ξέχασα τον κωδικό")
                }
            } else if (mode == AccountFormMode.RESET) {
                TextButton(onClick = onCancelReset, enabled = !isBusy, modifier = Modifier.align(Alignment.End)) {
                    Text("Πίσω στη σύνδεση")
                }
            }
        }
    }
}
