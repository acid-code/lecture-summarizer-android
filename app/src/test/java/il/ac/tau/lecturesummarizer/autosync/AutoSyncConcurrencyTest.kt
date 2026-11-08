package il.ac.tau.lecturesummarizer.autosync
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[34])
class AutoSyncConcurrencyTest{
 @Test fun concurrentNotFoundWritesDoNotLoseRowsAcrossStoreInstances(){
  val ctx=ApplicationProvider.getApplicationContext<Application>();AutoSyncStore(ctx).resetNotFound()
  val pool=Executors.newFixedThreadPool(8)
  val futures=(0 until 30).map{i->pool.submit{AutoSyncStore(ctx).addNotFound(NotFoundItem("parallel-$i","Course","lecture",i.toLong(),1))}}
  futures.forEach{it.get(10,TimeUnit.SECONDS)};pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))
  val rows=AutoSyncStore(ctx).notFoundItems();assertEquals(30,rows.size);assertEquals(30,rows.map{it.key}.toSet().size)
 }
}
