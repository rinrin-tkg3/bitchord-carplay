package com.music.bitchord.ui.components

import android.view.Choreographer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Person
import com.music.bitchord.carplay.CarPlayWebBridge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.components.floatingtabbar.FlatSelectionPill
import com.music.bitchord.ui.components.floatingtabbar.GlassPillMotion
import com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill
import com.music.bitchord.ui.components.floatingtabbar.PILL_GROW_HEIGHT
import com.music.bitchord.ui.components.floatingtabbar.pillPlacement
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.theme.stableStatusBarInsets
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/**
 * The bar's own height, above whatever inset it is sitting under.
 *
 * The single source of truth for it: the bar lays itself out to this, and
 * everything that has to clear the bar — page content padding, either top
 * backdrop, fixed headers that sit directly beneath it — measures from here
 * rather than from a copy of the number.
 */
val TopBarContentHeight = 52.dp

/**
 * The breathing room between the bar's bottom edge and the first thing under
 * it, so content rests below the glass instead of against it.
 */
val TopBarContentGap = 12.dp

/**
 * How much of each end of the bar is spoken for, so a long title truncates
 * instead of running under what sits there.
 *
 * Only ever consumed through the larger of the two — see the title's padding.
 * They are kept apart rather than collapsed into one number because they
 * describe two different things, and the wordmark is the one that changes when
 * the logo or the Dev badge does.
 */
private val BackInset = 54.dp
private val WordmarkInset = 96.dp
private val ActionsInset = 56.dp

/** Between a filling accessory and the actions pill. */
private val ACCESSORY_GAP = 8.dp

/** What the leading end of the bar needs: a back button, or the wordmark. */
private fun leadingInset(hasBack: Boolean): Dp = if (hasBack) BackInset else WordmarkInset

/**
 * How far down the window the bar actually ends: the status bar inset it is
 * pinned under, plus its own height.
 *
 * This has to be read at composition rather than baked in as a constant — the
 * inset is a property of the device and of the window, not of the app. A phone
 * with a cutout, one without, and a freeform window with no status bar at all
 * are all different numbers, and a fixed guess is wrong on all but one of them:
 * too tight and content is clipped under the bar, too loose and every page
 * opens on a band of empty space.
 *
 * The inset is read whether the status bar is showing or not: the player can
 * hide it while it is open, and every page under the player would otherwise
 * slide up into the space it left behind.
 */
@Composable
fun topBarHeight(): Dp =
    stableStatusBarInsets().asPaddingValues().calculateTopPadding() + TopBarContentHeight

/**
 * Where page content should start: clear of the bar, plus [TopBarContentGap].
 */
@Composable
fun topBarContentPadding(): Dp = topBarHeight() + TopBarContentGap

/**
 * The top bar's content — title, back affordance, actions — over no blur of its
 * own.
 *
 * The app mounts it with [transparentBackdrop] and [artworkPageChrome] on every
 * page, in either material: no full-width pane, just floating circles over the
 * shared top gradient — liquid glass when that is on, the navbar's blur when it
 * is not. The bounded-pane path ([TopBarBlur] underneath, lower hairline, solid
 * fill under Reduce dynamic blur) is kept for previews only.
 *
 * Apple Music behaviour: the big in-list header owns the title at rest;
 * once the list scrolls, the small centered title fades in.
 */
@Composable
fun FrostedTopBar(
    title: String,
    // A lambda, not a value: this flips as a feed leaves its very top, and
    // reading it in the caller would recompose the whole app on that frame of
    // the scroll.
    scrolled: () -> Boolean,
    modifier: Modifier = Modifier,
    /** Leaves the whole bar transparent so the page's own top gradient is the backdrop. */
    transparentBackdrop: Boolean = false,
    /** Circular back surface and no collapsing title, for artwork-led pages. */
    artworkPageChrome: Boolean = false,
    /** Source sampled by floating top-bar surfaces when liquid glass is off. */
    backButtonHazeState: HazeState? = null,
    trailingTitle: String? = null,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    // A lambda, not a value: the drag changes every frame, and reading it in
    // the caller would recompose the whole app on each one.
    pullFraction: () -> Float = { 0f },
    /**
     * A page control filling the bar from the leading gutter to [actions] — the
     * Search tab's field — set on a pill of its own; see [AccessoryPill].
     * Root tabs have no back button there for it to collide with.
     */
    accessory: (@Composable () -> Unit)? = null,
    trailingAccessory: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useFloatingChrome = artworkPageChrome ||
        (LocalLiquidGlassEnabled.current && isGlassSupported())
    val titleAlpha by animateFloatAsState(
        targetValue = if (scrolled() || trailingTitle != null) 1f else 0f,
        animationSpec = tween(220),
        label = "topBarTitleAlpha",
    )
    // Every bounded bar uses the same hairline, whether its pane is blurred or
    // solid. A transparent artwork page has no pane edge for a line to mark.
    val dividerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline.copy(
            alpha = when {
                transparentBackdrop -> 0f
                else -> 0.6f
            },
        ),
        animationSpec = tween(220),
        label = "topBarDivider",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (reduceDynamicBlur && !transparentBackdrop) {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                } else Modifier,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(stableStatusBarInsets())
                .height(TopBarContentHeight),
        ) {
            /* Temporarily hidden: keep the scrolling center-title implementation
             * intact so it can be restored without rebuilding its transitions.
             *
            // Artwork pages keep their large in-page heading and never create a
            // duplicate title in the status bar, including in accessibility.
            if (!artworkPageChrome) {
                AnimatedContent(
                    targetState = trailingTitle,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(260)) + slideInHorizontally(animationSpec = tween(260)) { it / 3 }) togetherWith
                            (fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200)) { -it / 3 })
                    },
                    label = "topBarTitleAnimation",
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Reserve room for whatever flanks the title — and reserve
                        // the *same* room on both sides. Equal is the whole point:
                        // the title is centered within this padded box, so an inset
                        // that differs end to end moves it off the bar's centre by
                        // half that difference. Reserving what each side actually
                        // needs (96dp for the wordmark, 56dp for the actions) put
                        // every root tab's title 20dp right of centre, which is
                        // visible against a status bar clock that is not.
                        .padding(horizontal = max(leadingInset(onBack != null), ActionsInset))
                        .fillMaxWidth()
                        .graphicsLayer { alpha = titleAlpha },
                ) { trailing ->
                    if (trailing != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = trailing,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
             */
            // On a pushed page the back affordance is always visible, since
            // there is no large in-list header to fall back on.
            if (onBack != null) {
                if (useFloatingChrome) {
                    ArtworkPageBackButton(
                        onClick = onBack,
                        hazeState = backButtonHazeState,
                        // The surface edge aligns with the floating navbar. The
                        // padding belongs outside the circle; its icon remains
                        // centred in the same 44dp control in every material.
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = BAR_GUTTER),
                    )
                } else {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    ) {
                        Icon(
                            SfIcons.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // No wordmark at the leading end: the root tabs carry it above
            // their large heading instead (LargePageTitle in :sharedUi).
            // The accessory takes everything from the leading gutter up to the
            // actions, which keep their own width at the trailing end.
            val edge = if (useFloatingChrome) BAR_GUTTER else 4.dp
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .then(if (accessory != null) Modifier.fillMaxWidth().padding(start = edge) else Modifier)
                    // The page's gutter (the navbar keeps Apple's wider one);
                    // the pill's inset below is internal padding around the
                    // icons, not extra margin.
                    .padding(end = edge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (accessory != null) {
                    Box(Modifier.weight(1f)) {
                        AccessoryPill(content = accessory)
                    }
                    Spacer(Modifier.width(ACCESSORY_GAP))
                }
                if (trailingAccessory != null) {
                    trailingAccessory()
                    Spacer(Modifier.width(ACCESSORY_GAP))
                }
                if (useFloatingChrome) {
                    ArtworkPageActions(hazeState = backButtonHazeState, content = actions)
                } else {
                    actions()
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
        RefreshPuck(refreshing = refreshing, pullFraction = pullFraction)
    }
}

/** Circular floating back affordance, material-matched to the navbar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ArtworkPageBackButton(
    onClick: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val contentColor = if (useLiquidGlass && !reduceDynamicBlur) {
        glassContentColor()
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(TOP_CONTROL_SIZE)
            .then(artworkPageSurface(shape = shape, hazeState = hazeState)),
    ) {
        Icon(
            SfIcons.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = contentColor,
        )
    }
}

/** One shared pill for every action at the right of a floating top bar. */
@Composable
private fun ArtworkPageActions(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ActionsRow(
        modifier = modifier
            .then(artworkPageSurface(shape = CircleShape, hazeState = hazeState))
            // Keep the right edge fixed while a new action opens room to its
            // left. The surface itself therefore grows instead of jumping to
            // its new width in a single frame.
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            // One profile target with no inset is a true circle, the back
            // button's size. Once another action exists, Apple's inset opens at
            // both ends. This is layout padding inside the surface, not an
            // outer margin, so BAR_GUTTER remains unchanged.
            .artworkActionEdgePadding(),
    ) {
        // Apple's pill is as tall as its back button, 44pt, where the actions'
        // own 48dp touch targets stood it 4dp taller than the circle beside it.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides TOP_CONTROL_SIZE) {
            content()
        }
    }
}

/**
 * The actions side by side, centred on the pill's height, with Apple's gap
 * between each and the one before it.
 *
 * Each gap opens with the width of the action after it, so one sliding in from
 * nothing — the download ring — brings its gap with it rather than the row
 * jumping by a gap's width the frame it appears.
 */
@Composable
private fun ActionsRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val target = TOP_CONTROL_SIZE.roundToPx()
        val gap = ACTION_GAP.toPx()
        val gaps = placeables.mapIndexed { index, placeable ->
            if (index == 0) 0 else (gap * (placeable.width.toFloat() / target).coerceIn(0f, 1f)).roundToInt()
        }
        val width = placeables.sumOf { it.width } + gaps.sum()
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEachIndexed { index, placeable ->
                x += gaps[index]
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

/**
 * The bar's accessory — the Search tab's field — as a squircle pill at the
 * height of the controls beside it, in the field's own solid fill.
 *
 * Deliberately the same with Liquid Glass on as off: the field is somewhere
 * to type, and on glass its hint and text took the glass's content colour
 * over whatever page was scrolling under it. The controls beside it still
 * follow the glass setting.
 */
@Composable
private fun AccessoryPill(content: @Composable () -> Unit) {
    val shape = remember { SquirclePillShape() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_CONTROL_SIZE)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        CompositionLocalProvider(LocalSearchFieldOnSurface provides true, content = content)
    }
}

/**
 * Edge padding that collapses only for the profile-only state.
 *
 * Invisible/animating action slots can measure between zero and a full target,
 * so the natural content width—not the number of emitted composables—is the
 * reliable source of truth. At exactly one icon target the pill stays circular;
 * above that it gains Apple's inset while retaining fully round ends.
 */
private fun Modifier.artworkActionEdgePadding(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val oneActionWidth = TOP_CONTROL_SIZE.roundToPx()
    val extraActionFraction = ((placeable.width - oneActionWidth).toFloat() / oneActionWidth)
        .coerceIn(0f, 1f)
    val edgePadding = (ACTION_EDGE_INSET.roundToPx() * extraActionFraction).roundToInt()
    val width = (placeable.width + edgePadding * 2).coerceIn(constraints.minWidth, constraints.maxWidth)

    layout(width, placeable.height) {
        placeable.placeRelative(edgePadding, 0)
    }
}

/** The navbar's exact material choice, reusable by every floating top control. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun artworkPageSurface(
    shape: CornerBasedShape,
    hazeState: HazeState?,
): Modifier {
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    return Modifier
        .clip(shape)
        .then(
            when {
                useLiquidGlass -> Modifier.liquidGlass(shape)
                reduceDynamicBlur || hazeState == null -> Modifier.background(container)
                else -> Modifier.optimizedHazeEffect(
                    state = hazeState,
                    style = HazeMaterials.regular(container),
                )
            },
        )
        // Keep the same explicit hairline as FloatingBottomBar. Liquid glass
        // also has its refractive highlight, but the navbar retains this edge
        // so these surfaces do as well.
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * The account affordance at the right end of the bar.
 *
 * It is the signed-in Google account's own photo — the same one YouTube Music
 * shows there — and tapping it opens Settings, where the account lives. Signed
 * out, or before the account menu has come back, it falls back to a person
 * glyph on a filled circle so the tap target never disappears.
 *
 * The hairline ring is what keeps a photo with light edges from dissolving into
 * the bar's glass; it is the same one thumbnails elsewhere carry.
 */
@Composable
fun TopBarAccountButton(
    account: Account?,
    onClick: () -> Unit,
    onSwipeProfile: ((forward: Boolean) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val translation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Wrapped in an IconButton so it keeps the touch target, the ripple and the
    // spacing every other action in this bar has.
    IconButton(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { translationY = translation.value }
            .pointerInput(onSwipeProfile) {
                if (onSwipeProfile == null) return@pointerInput
                var drag = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount -> change.consume(); drag += amount },
                    onDragEnd = {
                        if (kotlin.math.abs(drag) < 28f) return@detectVerticalDragGestures
                        if (!onSwipeProfile.invoke(drag > 0f)) scope.launch {
                            translation.snapTo(if (drag > 0f) 9f else -9f)
                            translation.animateTo(0f, spring())
                        }
                    },
                )
            },
    ) {
        val photo = account?.thumbnailUrl
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = stringResource(R.string.switch_account),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .thumbnailBorder(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = stringResource(R.string.switch_account),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The refresh indicator: a round puck that slides out from under the bar's
 * bottom edge as the list is pulled, Chrome-style.
 *
 * It is drawn by the bar rather than by the pull-to-refresh box, so it lands on
 * top of the glass instead of behind it, and it is clipped to the band just
 * below the bar so it emerges from the edge instead of appearing over the
 * status bar. Its position is the pull state's own distance fraction — which
 * the pull box holds at 1 while refreshing and animates back to 0 when done —
 * so the slide down, the rest while loading and the slide back up are all one
 * value. The arc fills with the drag, then spins once the refresh is away.
 *
 * Laid out at zero height, so the bar keeps its size and the puck overhangs
 * the content without taking any touches from it.
 */
@Composable
private fun RefreshPuck(refreshing: Boolean, pullFraction: () -> Float, modifier: Modifier = Modifier) {
    val currentFraction by rememberUpdatedState(pullFraction)
    val pulling by remember { derivedStateOf { currentFraction() > 0f } }
    if (!refreshing && !pulling) return

    val travel = with(LocalDensity.current) { (PUCK_REST + PUCK_SIZE).toPx() }
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                layout(placeable.width, 0) { placeable.place(0, 0) }
            }
            .fillMaxWidth()
            .height(PUCK_REST + PUCK_SIZE + PUCK_OVERSHOOT + 12.dp)
            .clipToBounds(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val f = currentFraction().coerceIn(0f, 2f)
                    // Past the threshold the puck keeps following, but
                    // reluctantly, like the list under it.
                    val eased = if (f <= 1f) f else 1f + (f - 1f) * 0.35f
                    translationY = travel * eased - PUCK_SIZE.toPx()
                    alpha = (f * 3f).coerceAtMost(1f)
                }
                .shadow(6.dp, CircleShape)
                .size(PUCK_SIZE)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val indicatorModifier = Modifier.size(22.dp)
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = indicatorModifier,
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                CircularProgressIndicator(
                    progress = { (currentFraction() * 0.8f).coerceIn(0f, 0.8f) },
                    modifier = indicatorModifier.graphicsLayer {
                        rotationZ = currentFraction().coerceIn(0f, 2f) * 180f
                    },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                )
            }
        }
    }
}

private val PUCK_SIZE = 40.dp

/**
 * The floating top controls' height — the back circle and the actions pill
 * alike — and each action's slot in the pill: Apple's 44pt.
 */
private val TOP_CONTROL_SIZE = 44.dp

/**
 * Between two actions in the pill, and from each end to the action there,
 * measured off Apple Music's share-and-more pill: 101pt wide, its glyphs
 * centred 23.4pt in from the ends and 54pt apart.
 */
private val ACTION_GAP = 10.dp
private val ACTION_EDGE_INSET = 1.5.dp

/** How far below the bar's edge the puck rests while a refresh runs. */
private val PUCK_REST = 16.dp

/** Headroom for the damped travel past the threshold. */
private val PUCK_OVERSHOOT = 20.dp

/**
 * The account photo's diameter.
 *
 * Smaller than an icon's 24dp box: a filled circle carries more weight than a
 * glyph does, and at 24 it sat heavier in the bar than the wordmark opposite it.
 */
/**
 * Compact 3-icon Liquid Glass theme selector (Dark / Light / System) placed
 * beside [TopBarAccountButton] on the Home top bar.
 *
 * Uses the exact same [Modifier.liquidGlass] surface, [FlatSelectionPill] and
 * [GlassSelectionPill] lens refraction physics as [GlassNavBar] / [FloatingTabBar].
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TopBarThemeSelector(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val themeMode by AppSettings.themeMode.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    val modes = remember {
        listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)
    }
    val selectedIndex = when (themeMode) {
        ThemeMode.DARK -> 0
        ThemeMode.LIGHT -> 1
        ThemeMode.SYSTEM -> 2
    }

    val pill = remember { GlassPillMotion(scope, selectedIndex) }
    pill.reduceMotion = reduceAnimation

    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val glassPill = useLiquidGlass && !reduceDynamicBlur
    val barGlass = rememberLayerBackdrop()
    val tabRow = rememberLayerBackdrop()

    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var lastHapticTab by remember { mutableIntStateOf(selectedIndex) }
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val tabCount = modes.size
    val tabWidthPx = if (rowSize.width > 0) rowSize.width.toFloat() / tabCount else 0f
    val tabStepPx = tabWidthPx
    pill.stepDp = tabStepPx / density.density
    val currentTabStepPx by rememberUpdatedState(tabStepPx)
    val currentIsRtl by rememberUpdatedState(isRtl)

    val pillInset = 4.dp
    val padPx = with(density) { pillInset.toPx() }
    val rowHeightPx = rowSize.height.toFloat()
    val restSize = Size(tabWidthPx, rowHeightPx)
    val liftedHeightPx = rowHeightPx + padPx * 2f + with(density) { PILL_GROW_HEIGHT.toPx() }
    val liftedSize = if (rowHeightPx > 0f) {
        Size(liftedHeightPx * tabWidthPx / rowHeightPx, liftedHeightPx)
    } else {
        restSize
    }
    val pillCenterInRow: (Float) -> Offset = { position ->
        val x = position * tabStepPx + tabWidthPx / 2f
        Offset(if (isRtl) rowSize.width - x else x, rowHeightPx / 2f)
    }
    val showPill = tabWidthPx > 0f

    LaunchedEffect(selectedIndex) {
        pill.animateTo(selectedIndex)
        lastHapticTab = selectedIndex
    }

    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val contentColor = if (glassPill) glassContentColor() else MaterialTheme.colorScheme.onSurface
    val selectedColor = contentColor
    val unselectedColor = contentColor.copy(alpha = 0.65f)
    val indicatorColor = if (glassPill) {
        glassIndicatorColor()
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }

    Box(modifier = modifier.height(TOP_CONTROL_SIZE)) {
        CompositionLocalProvider(LocalGlassExport provides barGlass) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(artworkPageSurface(shape = pillShape, hazeState = hazeState))
                    .padding(pillInset),
            ) {
                if (showPill && (!glassPill || pill.isFlat)) {
                    FlatSelectionPill(
                        color = indicatorColor,
                        modifier = Modifier
                            .matchParentSize()
                            .pillPlacement(
                                center = { pillCenterInRow(pill.position) },
                                size = { pill.liveSize(restSize, liftedSize) },
                            ),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .onSizeChanged { rowSize = it }
                        .layerBackdrop(tabRow)
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            var dragStartIndex = currentSelectedIndex
                            var direction = 1f
                            var step = 0f
                            var holding = false
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    step = currentTabStepPx
                                    holding = step > 0f
                                    if (!holding) return@detectHorizontalDragGestures
                                    direction = if (currentIsRtl) -1f else 1f
                                    totalDrag = 0f
                                    dragStartIndex = currentSelectedIndex
                                    pill.startDrag()
                                },
                                onDragCancel = {
                                    if (holding) pill.release(currentSelectedIndex)
                                    holding = false
                                },
                                onDragEnd = {
                                    if (!holding) return@detectHorizontalDragGestures
                                    holding = false
                                    val absoluteIndexFloat = (dragStartIndex + totalDrag / step)
                                        .coerceIn(0f, modes.lastIndex.toFloat())
                                    val newIndex = absoluteIndexFloat.roundToInt()
                                    pill.release(newIndex)
                                    val newMode = modes[newIndex]
                                    if (newMode != AppSettings.themeMode.value) {
                                        AppSettings.setThemeMode(newMode)
                                    }
                                },
                                onHorizontalDrag = { _, delta ->
                                    if (!holding) return@detectHorizontalDragGestures
                                    totalDrag += delta * direction
                                    val absoluteIndexFloat = (dragStartIndex + totalDrag / step)
                                        .coerceIn(0f, modes.lastIndex.toFloat())
                                    pill.dragTo(absoluteIndexFloat)
                                    val approximateTab = absoluteIndexFloat.roundToInt()
                                    if (approximateTab != lastHapticTab) {
                                        haptics.play(Haptic.Tick)
                                        lastHapticTab = approximateTab
                                    }
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    modes.forEachIndexed { index, mode ->
                        val isSelected = index == selectedIndex
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.08f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            label = "themeIconScale",
                        )
                        val icon = when (mode) {
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                            ThemeMode.LIGHT -> SfIcons.Brightness
                            ThemeMode.SYSTEM -> SfIcons.Gradient
                        }
                        Box(
                            modifier = Modifier
                                .width(34.dp)
                                .fillMaxHeight()
                                .clip(pillShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!isSelected) {
                                        haptics.play(Haptic.Select)
                                        AppSettings.setThemeMode(mode)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = mode.name,
                                tint = if (isSelected) selectedColor else unselectedColor,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                            )
                        }
                    }
                }
            }
        }

        if (glassPill && showPill && !pill.isFlat) {
            GlassSelectionPill(
                motion = pill,
                fillColor = indicatorColor,
                barGlass = barGlass,
                tabRow = tabRow,
                modifier = Modifier
                    .matchParentSize()
                    .pillPlacement(
                        center = { pillCenterInRow(pill.position) + Offset(padPx, padPx) },
                        size = { pill.liveSize(restSize, liftedSize) },
                    ),
            )
        }
    }
}

/**
 * Compact Liquid Glass FPS counter pill displayed at the top-right of the Home screen.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TopBarFpsCounter(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    var fps by remember { mutableIntStateOf(60) }
    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var frameCount = 0
        var startNanos = 0L
        var active = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                if (startNanos == 0L) {
                    startNanos = frameTimeNanos
                } else {
                    frameCount++
                    val elapsed = frameTimeNanos - startNanos
                    if (elapsed >= 500_000_000L) {
                        fps = ((frameCount * 1_000_000_000.0) / elapsed).roundToInt().coerceIn(1, 240)
                        frameCount = 0
                        startNanos = frameTimeNanos
                    }
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose {
            active = false
            choreographer.removeFrameCallback(callback)
        }
    }

    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val useGlass = useLiquidGlass && !reduceDynamicBlur
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val contentColor = if (useGlass) glassContentColor() else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .height(28.dp)
            .then(artworkPageSurface(shape = pillShape, hazeState = hazeState))
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$fps FPS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = contentColor.copy(alpha = 0.88f),
            maxLines = 1,
        )
    }
}

/**
 * Compact CarPlay / iPhone Companion button displayed in the Home top bar.
 * Shows a green dot when outputting audio to iPhone/CarPlay, or a blue dot when an iPhone is connected.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TopBarCarPlayButton(
    onClick: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val carPlaySession by CarPlayWebBridge.carPlaySession.collectAsStateWithLifecycle()
    val connectedClients by CarPlayWebBridge.connectedClients.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val useGlass = useLiquidGlass && !reduceDynamicBlur
    val contentColor = if (useGlass) glassContentColor() else MaterialTheme.colorScheme.onSurface

    val badgeColor = when {
        carPlaySession -> Color(0xFF30D158)
        connectedClients > 0 -> Color(0xFF0A84FF)
        else -> null
    }

    Box(
        modifier = modifier
            .size(TOP_CONTROL_SIZE)
            .then(artworkPageSurface(shape = CircleShape, hazeState = hazeState))
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.DirectionsCar,
            contentDescription = "CarPlay / iPhone 連携",
            tint = if (carPlaySession) Color(0xFF30D158) else contentColor,
            modifier = Modifier.size(20.dp),
        )
        if (badgeColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
            )
        }
    }
}

private val AVATAR_SIZE = 28.dp
