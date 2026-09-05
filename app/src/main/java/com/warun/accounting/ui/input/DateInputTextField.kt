package com.warun.accounting.ui.input

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import java.time.LocalDate

@Composable
internal fun DateInputTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    resetKey: Any? = null,
    onValueChange: (String) -> Unit
) {
    var fieldValue by remember(value, resetKey) {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length)
            )
        )
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { incoming ->
            val formatted = formatDateFieldValue(incoming)
            fieldValue = formatted
            onValueChange(formatted.text)
        },
        label = { Text(label) },
        trailingIcon = trailingIcon,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = modifier,
        singleLine = true
    )
}

internal fun formatDateFieldValue(
    incoming: TextFieldValue,
    currentYear: Int = LocalDate.now().year
): TextFieldValue {
    val formatted = formatDateInput(incoming.text, currentYear)
    return TextFieldValue(
        text = formatted,
        selection = TextRange(formatted.length)
    )
}
