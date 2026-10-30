package dev.svgpro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert

/**
 * ツールバー用アイコン（依存を増やさないため自前定義）。
 */
object AppIcons {

    private fun icon(name: String, path: String, stroke: Float = 1.6f, viewport: Float = 24f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewport,
            viewportHeight = viewport,
        ).apply {
            addPath(
                androidx.compose.ui.graphics.vector.addPathNodes(path),
                stroke = SolidColor(Color(0xFFE6EAF0)),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()

    private fun filledIcon(name: String, path: String, colorHex: Long = 0xFFE6EAF0): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                androidx.compose.ui.graphics.vector.addPathNodes(path),
                fill = SolidColor(Color(colorHex)),
            )
        }.build()

    val Select: ImageVector = icon(
        "Select",
        "M6,3 L6,17 L10.2,13.4 L12.6,19.4 L14.9,18.4 L12.5,12.6 L18,12 Z",
    )

    val Node: ImageVector = ImageVector.Builder(
        name = "Node",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M4,18 C8,8 16,8 20,18"),
            stroke = SolidColor(Color(0xFFE6EAF0)),
            strokeLineWidth = 1.6f,
            fill = null,
        )
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M4,18 m-2.4,0 a2.4,2.4 0 1 0 4.8,0 a2.4,2.4 0 1 0 -4.8,0"),
            fill = SolidColor(Color(0xFF42A5F5)),
        )
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M20,18 m-2.4,0 a2.4,2.4 0 1 0 4.8,0 a2.4,2.4 0 1 0 -4.8,0"),
            fill = SolidColor(Color(0xFF42A5F5)),
        )
    }.build()

    val Pen: ImageVector = icon(
        "Pen",
        "M4,20 L6.5,13.5 L15,5 C16.2,3.8 18.8,3.8 20,5 C21.2,6.2 21.2,8.8 20,10 L11.5,18.5 L5,21 Z M15,5 L19,9",
    )

    val Pencil: ImageVector = icon(
        "Pencil",
        "M4,20 C8,18 16,10 20,4 M4,20 C6,14 10,7 14,4",
        stroke = 1.4f,
    )

    val Rect: ImageVector = icon("Rect", "M4,6 L20,6 L20,18 L4,18 Z")
    val RRect: ImageVector = icon("RRect", "M7,5 L17,5 C19,5 21,7 21,9 L21,15 C21,17 19,19 17,19 L7,19 C5,19 3,17 3,15 L3,9 C3,7 5,5 7,5 Z")
    val Circle: ImageVector = icon("Circle", "M12,3 a9,9 0 1 0 0.001,0 Z M12,3 a9,9 0 1 1 -0.001,0")
    val Line: ImageVector = icon("Line", "M4,20 L20,4")
    val Polygon: ImageVector = icon("Polygon", "M12,3 L21,9.5 L17.5,20 L6.5,20 L3,9.5 Z")
    val Star: ImageVector = icon("Star", "M12,3 L14.5,9 L21,9.7 L16,14 L17.5,20.5 L12,17 L6.5,20.5 L8,14 L3,9.7 L9.5,9 Z")
    val Polyline: ImageVector = icon("Polyline", "M3,17 L9,9 L14,14 L21,5")

    val TextTool: ImageVector = icon("TextTool", "M5,6 L19,6 M12,6 L12,19 M8,19 L16,19")

    val Hand: ImageVector = icon(
        "Hand",
        "M8,12 L8,5.5 C8,4.7 8.7,4 9.5,4 C10.3,4 11,4.7 11,5.5 L11,11 M11,11 L11,4.5 C11,3.7 11.7,3 12.5,3 C13.3,3 14,3.7 14,4.5 L14,11 M14,11 L14,5.5 C14,4.7 14.7,4 15.5,4 C16.3,4 17,4.7 17,5.5 L17,13 C17,17 15,20 11.5,20 C8,20 6.5,18 5.5,15.5 L4.5,12.8 C4.2,12 4.7,11.2 5.5,11 C6.2,10.9 6.9,11.2 7.2,11.9 L8,13.5",
    )

    val Eyedropper: ImageVector = icon(
        "Eyedropper",
        "M14,4 L20,10 M16.5,6.5 C17.5,5.5 19,5.5 20,6.5 C21,7.5 21,9 20,10 L13,17 L8,19 L6,20 L4,18 L5,16 L7,11 Z",
    )

    val Undo: ImageVector = icon("Undo", "M8,6 L4,10 L8,14 M4,10 L15,10 C18,10 20,12 20,15 C20,18 18,20 15,20 L10,20")
    val Redo: ImageVector = icon("Redo", "M16,6 L20,10 L16,14 M20,10 L9,10 C6,10 4,12 4,15 C4,18 6,20 9,20 L14,20")

    val Save: ImageVector = filledIcon(
        "Save",
        "M17,3 L5,3 C3.9,3 3,3.9 3,5 L3,19 C3,20.1 3.9,21 5,21 L19,21 C20.1,21 21,20.1 21,19 L21,7 Z M12,19 C10.3,19 9,17.7 9,16 C9,14.3 10.3,13 12,13 C13.7,13 15,14.3 15,16 C15,17.7 13.7,19 12,19 Z M15,9 L5,9 L5,5 L15,5 Z",
    )

    val Fit: ImageVector = icon("Fit", "M4,9 L4,4 L9,4 M15,4 L20,4 L20,9 M20,15 L20,20 L15,20 M9,20 L4,20 L4,15 M9,12 L15,12")
    val Grid: ImageVector = icon("Grid", "M4,4 L20,4 L20,20 L4,20 Z M4,9.3 L20,9.3 M4,14.6 L20,14.6 M9.3,4 L9.3,20 M14.6,4 L14.6,20", stroke = 1.2f)
    val Layers: ImageVector = icon("Layers", "M12,3 L21,8 L12,13 L3,8 Z M5.5,12 L3,13.5 L12,18.5 L21,13.5 L18.5,12 M7,15.5 L3,17.8 L12,22.5 L21,17.8 L17,15.5")
    val Palette: ImageVector = icon(
        "Palette",
        "M12,3 C7,3 3,7 3,12 C3,17 7,21 12,21 C13.1,21 14,20.1 14,19 C14,18.5 13.8,18 13.5,17.7 C13.2,17.4 13,16.9 13,16.5 C13,15.4 13.9,14.5 15,14.5 L17,14.5 C19.8,14.5 21,12.5 21,10.5 C21,6.4 17,3 12,3 Z M7,12 C6.4,12 6,11.6 6,11 C6,10.4 6.4,10 7,10 C7.6,10 8,10.4 8,11 C8,11.6 7.6,12 7,12 Z M10,8.5 C9.4,8.5 9,8.1 9,7.5 C9,6.9 9.4,6.5 10,6.5 C10.6,6.5 11,6.9 11,7.5 C11,8.1 10.6,8.5 10,8.5 Z M14.5,8.5 C13.9,8.5 13.5,8.1 13.5,7.5 C13.5,6.9 13.9,6.5 14.5,6.5 C15.1,6.5 15.5,6.9 15.5,7.5 C15.5,8.1 15.1,8.5 14.5,8.5 Z",
    )
    val PathOps: ImageVector = icon(
        "PathOps",
        "M5,4 L13,4 L13,12 L5,12 Z M11,12 L19,12 L19,20 L11,20 Z",
        stroke = 1.4f,
    )
    val Front: ImageVector = icon("Front", "M12,3 L17,6 L12,9 L7,6 Z M12,10.5 L17,13.5 L12,16.5 L7,13.5 Z M12,18 L17,21 L7,21 Z")
    val Back: ImageVector = icon("Back", "M12,3 L17,6 L7,6 Z M12,7.5 L17,10.5 L12,13.5 L7,10.5 Z M7,13.5 L17,13.5 L12,21 Z")
    val Group: ImageVector = icon("Group", "M4,4 L10,4 L10,10 L4,10 Z M14,14 L20,14 L20,20 L14,20 Z M10,7 L14,7 M7,10 L7,17 M14,17 L10,17 M17,14 L17,7")
    val Ungroup: ImageVector = icon("Ungroup", "M4,4 L10,4 L10,10 L4,10 Z M14,14 L20,14 L20,20 L14,20 Z")
    val LockIcon: ImageVector = filledIcon("LockIcon", "M18,8 L17,8 L17,6 C17,3.2 14.8,1 12,1 C9.2,1 7,3.2 7,6 L7,8 L6,8 C4.9,8 4,8.9 4,10 L4,20 C4,21.1 4.9,22 6,22 L18,22 C19.1,22 20,21.1 20,20 L20,10 C20,8.9 19.1,8 18,8 Z M12,17 C10.9,17 10,16.1 10,15 C10,13.9 10.9,13 12,13 C13.1,13 14,13.9 14,15 C14,16.1 13.1,17 12,17 Z M15.1,8 L8.9,8 L8.9,6 C8.9,4.3 10.3,2.9 12,2.9 C13.7,2.9 15.1,4.3 15.1,6 Z")
    val UnlockIcon: ImageVector = icon("UnlockIcon", "M17,8 L8,8 C6.3,8 5,9.3 5,11 L5,19 C5,20.7 6.3,22 8,22 L16,22 C17.7,22 19,20.7 19,19 L19,11 C19,9.3 17.7,8 16,8 Z M17,19 L7,19 L7,11 L17,11 Z M15,8 L15,6 C15,4.3 13.7,3 12,3 C10.3,3 9,4.3 9,6 M12,14 L12,16")
    val EyeOpen: ImageVector = icon("EyeOpen", "M12,5 C6,5 3,12 3,12 C3,12 6,19 12,19 C18,19 21,12 21,12 C21,12 18,5 12,5 Z M12,15.5 C10.1,15.5 8.5,13.9 8.5,12 C8.5,10.1 10.1,8.5 12,8.5 C13.9,8.5 15.5,10.1 15.5,12 C15.5,13.9 13.9,15.5 12,15.5 Z")
    val EyeClosed: ImageVector = icon("EyeClosed", "M3,12 C3,12 6,19 12,19 C13.5,19 14.8,18.6 16,18 M19.5,16 C20.5,14.7 21,12 21,12 C21,12 18,5 12,5 C11,5 10.1,5.2 9.3,5.5 M4,4 L20,20 M12,8.5 C13.9,8.5 15.5,10.1 15.5,12 C15.5,12.5 15.4,13 15.2,13.4")
    val Plus: ImageVector = Icons.Filled.Add
    val Close: ImageVector = Icons.Filled.Close
    val Check: ImageVector = Icons.Filled.Check
    val Delete: ImageVector = Icons.Filled.Delete
    val Edit: ImageVector = Icons.Filled.Edit
    val MenuIcon: ImageVector = Icons.Filled.Menu
    val More: ImageVector = Icons.Filled.MoreVert
    val Up: ImageVector = icon("Up", "M12,5 L12,19 M6,11 L12,5 L18,11")
    val Down: ImageVector = icon("Down", "M12,19 L12,5 M6,13 L12,19 L18,13")
    val FlipH: ImageVector = icon("FlipH", "M12,3 L12,21 M7,7 L3,12 L7,17 M17,7 L21,12 L17,17")
    val FlipV: ImageVector = icon("FlipV", "M3,12 L21,12 M7,7 L12,3 L17,7 M7,17 L12,21 L17,17")
    val RotateCW: ImageVector = icon("RotateCW", "M20,12 C20,7.6 16.4,4 12,4 C7.6,4 4,7.6 4,12 C4,16.4 7.6,20 12,20 M20,12 L16.5,8.5 M20,12 L16.5,15.5")
    val Union: ImageVector = icon("Union", "M4,4 L14,4 L14,10 L20,10 L20,20 L10,20 L10,14 L4,14 Z")
    val Sub: ImageVector = icon("Sub", "M4,4 L14,4 L14,14 L4,14 Z M10,10 L20,10 L20,20 L10,20 Z", stroke = 1.4f)
    val Inter: ImageVector = ImageVector.Builder(
        name = "Inter",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M4,4 L14,4 L14,14 L4,14 Z"),
            stroke = SolidColor(Color(0x88E6EAF0)),
            strokeLineWidth = 1.4f,
            fill = null,
        )
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M10,10 L20,10 L20,20 L10,20 Z"),
            stroke = SolidColor(Color(0x88E6EAF0)),
            strokeLineWidth = 1.4f,
            fill = null,
        )
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M10,10 L14,10 L14,14 L10,14 Z"),
            fill = SolidColor(Color(0xFFE6EAF0)),
        )
    }.build()
    val Xor: ImageVector = ImageVector.Builder(
        name = "Xor",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M4,4 L14,4 L14,14 L4,14 Z"),
            stroke = SolidColor(Color(0x88E6EAF0)),
            strokeLineWidth = 1.4f,
            fill = null,
        )
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M10,10 L20,10 L20,20 L10,20 Z"),
            stroke = SolidColor(Color(0x88E6EAF0)),
            strokeLineWidth = 1.4f,
            fill = null,
        )
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M4,4 L14,4 L14,10 L10,10 L10,14 L4,14 Z M14,14 L20,14 L20,20 L10,20 L10,14 L14,14 Z"),
            fill = SolidColor(Color(0xFFE6EAF0)),
        )
    }.build()
}
