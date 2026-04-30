/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.test;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DTOTest {
  @Data
  public static class BaseDTO {

    private int pid;

    private String cateName;
  }

  @Data
  @EqualsAndHashCode(callSuper = true)
  public static class UserDTO extends BaseDTO implements Serializable {
    private Long userId;
    private OrderDTO[] orderDTOS;
    private Integer[] nums;
    private int[] nums2;
    private ColorEnum colorEnum;
    List<OrderDTO> orderDTOList;
    private Byte propByte;
    private Short propShort;
    private Integer propInteger;
    private Float propFloat;
    private Double propDouble;
    private int propinnt;
    private float propfloag;
    private double propdouble;
    private byte propbyte;
    private Character propCharacter;
    private char propchar;
    private Boolean propBoolean;
    private boolean propboolean;
    private long proplong;
    private short propshort;
    private String name;
    private List<String> listString;

    OrderDTO orderDTO;

    Date gmtCreate;

    Set<OrderDTO> orderDTOSet;
  }

  @Data
  public static class OrderDTO implements Serializable {

    private String itemName;

    private Long orderId;

    private Integer orderNum;
  }

  public enum ColorEnum {
    RED,
    GREEN,
    BLUE
  }

  @Test
  public void testFory() {
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    UserDTO obj = BeanMock.mockBean(UserDTO.class);
    obj.setColorEnum(ColorEnum.RED);
    Assert.assertEquals(fory.deserialize(fory.serialize(obj)), obj);
  }
}
