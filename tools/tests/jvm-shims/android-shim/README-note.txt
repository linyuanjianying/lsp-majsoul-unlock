Minimal android.* stand-ins for the JVM build-regression probe of GameTopControl
(run-top-control-build-probe.sh). Compiled only into the probe's private classpath,
ahead of android.jar, so the real unmodified sources can run on a plain JVM.
