package com.example.smartwalkapp.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartwalkapp.R
import com.example.smartwalkapp.ui.components.SmartWalkBottomBar
import com.example.smartwalkapp.ui.theme.DeepBlue
import com.example.smartwalkapp.ui.theme.ElectricBlue
import com.example.smartwalkapp.ui.theme.SmartWalkappTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateClick: () -> Unit = {},
    onGoogleMapsClick: () -> Unit = {},
    onVoiceAssistantClick: () -> Unit = {},
    onAddressClick: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {
        SmartWalkBottomBar(
            currentRoute = "home",
            onNavigate = { }
        )
    }
) {
    val currentAddress by viewModel.address.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    if (isLoading) {
        LoadingScreen()
    } else {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "SmartWalk",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 32.sp,
                            color = Color.White,
                            letterSpacing = (-1).sp
                        )
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(60.dp)
                                .clickable { },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "Logo",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { }) {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                contentDescription = "Notifications",
                                modifier = Modifier.size(32.dp),
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = DeepBlue
                    )
                )
            },
            bottomBar = bottomBar,
            containerColor = Color(0xFFF5F7FA)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                DeepBlue,
                                Color(0xFFF5F7FA)
                            ),
                            startY = 0f,
                            endY = 600f
                        )
                    )
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Ubicación Actual Card con Estilo Moderno
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddressClick() },
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    ElectricBlue.copy(alpha = 0.1f),
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MyLocation,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column {
                            Text(
                                "ESTÁS EN",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = ElectricBlue.copy(alpha = 0.6f),
                                letterSpacing = 2.sp
                            )
                            Text(
                                currentAddress,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1A1A),
                                lineHeight = 24.sp,
                                maxLines = 2
                            )
                        }
                    }
                }

                // Voice Assistant Button - El "Hero" de la app
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 24.dp)
                        .clickable { onVoiceAssistantClick() },
                    shape = RoundedCornerShape(32.dp),
                    color = ElectricBlue,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(140.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Asistente de Voz",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "TOCA PARA HABLAR",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f),
                            letterSpacing = 3.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Órbita Exterior (Círculos que giran)
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .graphicsLayer { rotationZ = rotation }
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color.White.copy(alpha = 0.4f), CircleShape)
                            .align(Alignment.TopCenter)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .align(Alignment.BottomCenter)
                    )
                }

                // Órbita Interior (Giro inverso)
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .graphicsLayer { rotationZ = -rotation * 1.5f }
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(ElectricBlue.copy(alpha = 0.6f), CircleShape)
                            .align(Alignment.CenterStart)
                    )
                }

                // Logo central (Sin fondo blanco, con pulso suave)
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                        }
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "SmartWalk",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .width(180.dp)
                    .height(6.dp),
                color = ElectricBlue,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
fun HomeLargeButton(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        shape = RoundedCornerShape(12.dp),
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BottomNavigationBar() {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Rounded.Navigation, contentDescription = "Navigate", modifier = Modifier.size(32.dp)) },
            label = { Text("NAVIGATE", fontWeight = FontWeight.Bold) },
            selected = true,
            onClick = {},
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Rounded.Explore, contentDescription = "Explore", modifier = Modifier.size(32.dp)) },
            label = { Text("EXPLORE", fontWeight = FontWeight.Bold) },
            selected = false,
            onClick = {}
        )
        NavigationBarItem(
            icon = { Icon(Icons.Rounded.Mic, contentDescription = "Voice", modifier = Modifier.size(32.dp)) },
            label = { Text("VOICE", fontWeight = FontWeight.Bold) },
            selected = false,
            onClick = {}
        )
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun HomeScreenPreview() {
    SmartWalkappTheme {
        HomeScreen()
    }
}
