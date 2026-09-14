package org.apache.fory.serializer;

import java.util.Date;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.testng.annotations.Test;

public class Bug4047Test {

  public static class SmartTorrent2 {
    private Date createdDate;

    public SmartTorrent2() {}

    public Date getCreatedDate() {
      return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
      this.createdDate = createdDate;
    }
  }

  @Test
  public void testSequentialSerializerWithDateField() {
    Fory fory =
        Fory.builder()
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();

    int id = SmartTorrent2.class.getName().hashCode() & 0x7FFFFFFF;
    fory.register(SmartTorrent2.class, id);

    SmartTorrent2 smartTorrent = new SmartTorrent2();
    smartTorrent.setCreatedDate(new Date());
    fory.serialize(smartTorrent);
  }
}
