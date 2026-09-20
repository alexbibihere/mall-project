package com.mall.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.common.BizException;
import com.mall.common.UserContext;
import com.mall.user.entity.Address;
import com.mall.user.mapper.AddressMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressMapper addressMapper;

    public List<Address> listMine() {
        return addressMapper.selectList(new LambdaQueryWrapper<Address>()
                .eq(Address::getUserId, UserContext.get())
                .orderByDesc(Address::getIsDefault));
    }

    @Transactional
    public Long add(Address addr) {
        addr.setId(null);
        addr.setUserId(UserContext.get());
        if (Boolean.TRUE.equals(addr.getIsDefault() != null && addr.getIsDefault() == 1)) {
            clearDefault();
        } else {
            addr.setIsDefault(0);
        }
        addressMapper.insert(addr);
        return addr.getId();
    }

    @Transactional
    public void update(Address addr) {
        Address db = mustOwn(addr.getId());
        if (addr.getIsDefault() != null && addr.getIsDefault() == 1) {
            clearDefault();
        }
        db.setProvince(addr.getProvince());
        db.setCity(addr.getCity());
        db.setDetail(addr.getDetail());
        db.setReceiver(addr.getReceiver());
        db.setPhone(addr.getPhone());
        if (addr.getIsDefault() != null) {
            db.setIsDefault(addr.getIsDefault());
        }
        addressMapper.updateById(db);
    }

    @Transactional
    public void delete(Long id) {
        mustOwn(id);
        addressMapper.deleteById(id); // 逻辑删除（@TableLogic）
    }

    /** 下单用：校验归属并返回快照文本。 */
    public String snapshot(Long addressId) {
        Address a = mustOwn(addressId);
        return a.getReceiver() + " " + a.getPhone() + " "
                + a.getProvince() + a.getCity() + a.getDetail();
    }

    /** MQ 消费端用：无登录上下文，按消息中的 userId 校验归属。 */
    public String snapshotFor(Long addressId, Long userId) {
        Address a = addressMapper.selectById(addressId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw BizException.of("地址不存在");
        }
        return a.getReceiver() + " " + a.getPhone() + " "
                + a.getProvince() + a.getCity() + a.getDetail();
    }

    private Address mustOwn(Long id) {
        Address a = addressMapper.selectById(id);
        if (a == null || !a.getUserId().equals(UserContext.get())) {
            throw BizException.of("地址不存在");
        }
        return a;
    }

    private void clearDefault() {
        addressMapper.update(null, new LambdaUpdateWrapper<Address>()
                .eq(Address::getUserId, UserContext.get())
                .eq(Address::getIsDefault, 1)
                .set(Address::getIsDefault, 0));
    }
}
