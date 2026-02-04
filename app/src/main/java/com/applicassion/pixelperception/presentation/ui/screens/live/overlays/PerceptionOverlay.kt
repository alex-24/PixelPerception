import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import kotlin.math.min

@Composable
fun PerceptionOverlay(
    grid: CoreOutputGrid,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {

        val cols = grid.width
        val rows = grid.height

        val cellW = size.width / cols
        val cellH = size.height / rows
        val baseR = 0.45f * min(cellW, cellH)

        var idx = 0
        for (r in 0 until rows) {
            val cy = (r + 0.5f) * cellH
            for (c in 0 until cols) {
                val cx = (c + 0.5f) * cellW
                val v = grid.values[idx++].coerceIn(0f, 1f)

                // simple encoding: radius + alpha
                val radius = baseR * v
                if (radius > 0.5f) {
                    drawCircle(
                        color = Color.Red.copy(alpha = (0.15f + 0.85f * v)),
                        radius = radius,
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}