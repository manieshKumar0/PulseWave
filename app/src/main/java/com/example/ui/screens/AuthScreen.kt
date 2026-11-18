package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.data.firebase.attemptAutoSignIn
import com.example.data.firebase.onGoogleSignInClicked
import com.example.ui.components.PulseWaveBrandLogo

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onSkip: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var isLoading by remember { mutableStateOf(false) }
    var isCheckingSilent by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isCheckingSilent = false
                onAuthSuccess()
            },
            onUnauthenticated = {
                isCheckingSilent = false
            },
            scope = scope
        )
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        // Background PNG (846x1859) rendered with ContentScale.Crop: compute exact screen Y coordinates
        // for the glowing arc (top rim ~0.159f, horizon crest ~0.430f, glowing wave ~0.484f).
        val imageAspect = 846f / 1859f
        val containerAspect = if (containerHeight > 0.dp) containerWidth / containerHeight else imageAspect
        val renderedImageHeight = if (containerAspect > imageAspect) {
            containerWidth / imageAspect
        } else {
            containerHeight
        }
        val verticalCropOffset = ((renderedImageHeight - containerHeight) / 2f).coerceAtLeast(0.dp)

        fun imageYToScreenY(fraction: Float): Dp =
            (renderedImageHeight * fraction - verticalCropOffset).coerceAtLeast(0.dp)

        val heroTopOffset = maxOf(
            imageYToScreenY(0.214f),
            statusBarTop + 28.dp
        )
        val estimatedHeroHeight = 138.dp
        val featuresTargetTop = imageYToScreenY(0.575f)
        val midGap = (featuresTargetTop - heroTopOffset - estimatedHeroHeight).coerceAtLeast(36.dp)
        val bottomGap = maxOf(navBarBottom + 36.dp, containerHeight * 0.11f)

        Image(
            painter = painterResource(id = R.drawable.bg_auth_teal_horizon),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Subtle atmospheric gradient scrim for crisp contrast on small and large displays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0x14061E24),
                        0.45f to Color.Transparent,
                        0.72f to Color(0x28051A20),
                        1.0f to Color(0x66041318)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = containerHeight)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(heroTopOffset))

            // Hero centered cleanly inside the glowing teal circular arc
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PulseWaveBrandLogo(
                    size = 72.dp,
                    showBackdrop = false
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "PULSEWAVE",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 5.5.sp,
                        shadow = Shadow(
                            color = Color(0x6603191F),
                            offset = Offset(0f, 3f),
                            blurRadius = 10f
                        )
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "MUSIC LIBRARY",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 4.sp,
                        shadow = Shadow(
                            color = Color(0x5503191F),
                            offset = Offset(0f, 2f),
                            blurRadius = 6f
                        )
                    ),
                    color = Color.White.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(midGap))

            // Feature boxes + indicator + Google sign-in lifted comfortably above the bottom edge
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    AuthFeatureTile(
                        icon = Icons.Rounded.AutoAwesome,
                        label = "AI\nRecommendations",
                        modifier = Modifier.weight(1f)
                    )
                    AuthFeatureTile(
                        icon = Icons.Rounded.GraphicEq,
                        label = "Live\nLyrics",
                        modifier = Modifier.weight(1f)
                    )
                    AuthFeatureTile(
                        icon = Icons.Rounded.Groups,
                        label = "PulseRooms",
                        modifier = Modifier.weight(1f)
                    )
                    AuthFeatureTile(
                        icon = Icons.Rounded.CloudDownload,
                        label = "Offline\nSync",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Minimalist pager accent indicator matching the reference composition
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.9f))
                    )
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .width(10.dp)
                                .height(3.5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.25f))
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                if (errorMessage != null) {
                    Surface(
                        color = Color(0xCC3B1317),
                        border = BorderStroke(1.dp, Color(0x66FF8A80)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = Color(0xFFFFD8D6),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Button(
                    onClick = {
                        errorMessage = null
                        isLoading = true
                        onGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isLoading = false
                                onAuthSuccess()
                            },
                            onAuthError = { msg ->
                                isLoading = false
                                errorMessage = msg
                            },
                            scope = scope,
                            onAuthCancelled = {
                                isLoading = false
                            }
                        )
                    },
                    enabled = !isLoading && !isCheckingSilent,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3EFE6),
                        contentColor = Color(0xFF1A1D21),
                        disabledContainerColor = Color(0xCCF3EFE6),
                        disabledContentColor = Color(0xFF474C52)
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 1.dp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("google_sign_in_button")
                ) {
                    if (isLoading || isCheckingSilent) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF1A1D21),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (isCheckingSilent) "Checking session..." else "Signing in...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.5.sp
                            )
                        )
                    } else {
                        GoogleGLogo(modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Sign in with Google",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(bottomGap))
        }

        // Top-right frosted Skip pill matching the reference screen
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 20.dp)
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(50))
                .clickable(enabled = !isLoading, onClick = onSkip)
                .testTag("skip_sign_in_button"),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Skip",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.White.copy(alpha = 0.94f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "Skip sign in",
                    tint = Color.White.copy(alpha = 0.94f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AuthFeatureTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label.replace("\n", " "),
                tint = Color.White.copy(alpha = 0.94f),
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 13.5.sp,
                fontWeight = FontWeight.Normal
            ),
            color = Color.White.copy(alpha = 0.84f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GoogleGLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val strokeWidth = diameter * 0.21f
        val inset = strokeWidth / 2f
        val arcSize = Size(diameter - strokeWidth, diameter - strokeWidth)
        val topLeft = Offset(inset, inset)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)

        // Red (top)
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 210f,
            sweepAngle = 108f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
        // Yellow (left)
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 148f,
            sweepAngle = 64f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
        // Green (bottom)
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 46f,
            sweepAngle = 104f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
        // Blue (bottom-right) + horizontal crossbar
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -5f,
            sweepAngle = 53f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke
        )
        val cy = size.height / 2f
        val cx = size.width / 2f
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(cx, cy),
            end = Offset(size.width - inset * 0.35f, cy),
            strokeWidth = strokeWidth * 0.92f,
            cap = StrokeCap.Butt
        )
    }
}
