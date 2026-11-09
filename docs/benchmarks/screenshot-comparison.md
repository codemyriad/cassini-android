# Screenshot comparison timing

D82 targets the slow Android test suites without reducing their checks. In green
CI run 42653267893, StateVariantsTest took 20.170 s for 116 cases, AppShellTest
19.596 s for 13, and BoardTestFontScaleSetupTest 14.000 s for one. Most of the
AppGraph and font-scale suite cost was in their first case.

Local profiling used JDK 17.0.21+9 on an arm64 Mac mini under the shared heavy
lock, with outputs on the external disk. The Mac StateVariants run passed all
116 cases in 6.332 s, and AppShell passed all 13 in 6.164 s. These runs omit PNG
verification and are not comparable to Linux CI screenshot timings. The
AppShell profile contained about 0.4 s of polling waits and 0.3 s of GC. A
Serial GC trial did not improve the result and is not part of this change.

## Change and limits

The pinned differ 0.3.0 SimpleImageComparator visits columns first. BufferedImage
and the diff mask store pixels in row order. RowMajorComparator changes only
that traversal for equal-size images under the existing pointwise policy. It
calls the library's distance function, uses its maxDistance, and writes every
mask value. Size, mask or policy cases outside that path use the original
comparator. The changed-area validator is the same instance as before.

Roborazzi already has a whole-image equality fast path. This change speeds up
its fallback comparison; it does not affect that fast path or remove any of
StateVariantsTest's 116 captures. No golden, distance tolerance, changed-area
threshold, test assertion or animation time changes.

## Measured operation

The stand-alone JVM harness below reads the committed 780 x 1688
`ui/src/test/screenshots/variants/library-loading.png`. It changes one blue
channel bit in a copy. That defeats whole-image equality while remaining below
the existing pixel tolerance. Both versions report zero changed pixels and the
same full pixel count. This is image processing over existing fixtures, not a
new Mac golden recording.

Four warmup rounds run both comparators. Nine timed rounds alternate their
order in one JVM with a 512 MiB heap. File loading, fixture creation and result
assertions are outside the timed region. The helper also asserts that each
fallback comparator runs once per round, rather than being bypassed by image
equality.

| Traversal | Median per comparison |
| --- | ---: |
| Pinned library, columns first | 5.503 ms |
| Rows first | 4.692 ms |

The measured fallback operation is about 15% faster. This is not a measured
end-to-end CI improvement; unchanged images bypass the operation, and startup,
rendering, PNG loading and other test work remain.

## Reproduction

Compile the UI unit-test classes first. Compile and run this temporary helper
with JDK 17, `-Djava.awt.headless=true` and `-Xmx512m`. Its classpath needs the UI
unit-test classes, the pinned roborazzi-core-jvm and roborazzi-painter-jvm jars,
differ-jvm, kotlin-stdlib and the compile SDK's android.jar (only for API types
that the painter mentions). Pass the fixture path as the first argument. Keep
the helper and its output outside the checkout; it is not an ordinary CI test.
On the shared Mac, use the heavy lock and memory admission for these commands.

```java
import com.github.takahirom.roborazzi.*;
import com.dropbox.differ.*;
import io.codemyriad.cassini.ui.RowMajorComparator;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

public class GoldenComparatorBench {
  public static void main(String[] args) throws Exception {
    BufferedImage image = ImageIO.read(new File(args[0]));
    BufferedImage changed = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    var graphics = changed.createGraphics(); graphics.drawImage(image, 0, 0, null); graphics.dispose();
    int x = image.getWidth() / 2, y = image.getHeight() / 2;
    changed.setRGB(x, y, changed.getRGB(x, y) ^ 1);
    var left = new AwtRoboCanvas(image.getWidth(), image.getHeight(), true, BufferedImage.TYPE_INT_ARGB);
    var right = new AwtRoboCanvas(image.getWidth(), image.getHeight(), true, BufferedImage.TYPE_INT_ARGB);
    left.drawImage(image, AwtRoboCanvas.CompositeMode.Src);
    right.drawImage(changed, AwtRoboCanvas.CompositeMode.Src);
    ImageComparator reference = new RoborazziOptions.CompareOptions().getImageComparator();
    ImageComparator candidate = new RowMajorComparator(reference);
    double[][] times = new double[2][9];
    int[] calls = {0, 0};
    ImageComparator[] comparators = {
      (l, r, m) -> { calls[0]++; return reference.compare(l, r, m); },
      (l, r, m) -> { calls[1]++; return candidate.compare(l, r, m); }
    };
    for (int warm = 0; warm < 4; warm++) for (var c : comparators) left.differ(right, 1.0, c);
    for (int round = 0; round < 9; round++) for (int k = 0; k < 2; k++) {
      int index = (round % 2 == 0) ? k : 1-k;
      long start = System.nanoTime();
      var result = left.differ(right, 1.0, comparators[index]);
      times[index][round] = (System.nanoTime() - start) / 1_000_000.0;
      if (result.getPixelDifferences() != 0 || result.getPixelCount() != image.getWidth()*image.getHeight())
        throw new AssertionError(result.toString());
    }
    for (int index = 0; index < 2; index++) {
      System.out.println((index == 0 ? "column" : "row") + " samples_ms=" + Arrays.toString(times[index]));
      Arrays.sort(times[index]); System.out.println("median_ms=" + times[index][4]);
    }
    if (calls[0] != 13 || calls[1] != 13) throw new AssertionError("fallback was not exercised: " + Arrays.toString(calls));
    System.out.println("fallback_calls=" + Arrays.toString(calls));
    left.release(); right.release();
  }
}
```
