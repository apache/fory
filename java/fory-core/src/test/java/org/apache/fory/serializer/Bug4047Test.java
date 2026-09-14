package org.apache.fory.serializer;

import static org.testng.Assert.assertEquals;

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
    Date createdDate = new Date(123456789L);
    smartTorrent.setCreatedDate(createdDate);

    byte[] bytes = fory.serialize(smartTorrent);
    SmartTorrent2 result = (SmartTorrent2) fory.deserialize(bytes);

    assertEquals(result.getCreatedDate(), createdDate);
  }
}
