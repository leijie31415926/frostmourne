package com.autohome.frostmourne.monitor.service.account.impl;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Resource;

import com.autohome.frostmourne.core.contract.PagerContract;
import com.autohome.frostmourne.core.contract.ProtocolException;
import com.autohome.frostmourne.monitor.contract.UserContract;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.domain.UserInfo;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IUserInfoRepository;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IUserRoleRepository;
import com.autohome.frostmourne.monitor.service.account.IUserInfoService;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserInfoService implements IUserInfoService {

    @Resource
    private IUserInfoRepository userInfoRepository;

    @Resource
    private IUserRoleRepository userRoleRepository;

    @Override
    public boolean insert(UserContract contract) {
        Optional<UserInfo> optionalUserInfo = userInfoRepository.findByAccount(contract.getAccount());
        if (optionalUserInfo.isPresent()) {
            throw new ProtocolException(5690, "账号已经存在");
        }
        UserInfo userInfo = toInfo(contract);
        userInfo.setCreator(contract.getCreator());
        userInfo.setModifier(contract.getModifier());
        Date now = new Date();
        userInfo.setCreateAt(now);
        userInfo.setModifyAt(now);
        boolean success = userInfoRepository.insert(userInfo);
        if (success) {
            userRoleRepository.modifyByContract(contract);
        }
        return success;
    }

    @Override
    public boolean delete(Long userId) {
        return userInfoRepository.delete(userId);
    }

    @Override
    public boolean update(UserContract contract) {
        UserInfo userInfo = toInfo(contract);
        userInfo.setModifier(contract.getModifier());
        userInfo.setModifyAt(new Date());
        boolean success = userInfoRepository.update(userInfo);
        if (success) {
            userRoleRepository.modifyByContract(contract);
        }
        return success;
    }

    @Override
    public PagerContract<UserContract> findPage(int pageIndex, int pageSize, Long id, String account, Long teamId) {
        PagerContract<UserInfo> pagerInfo = userInfoRepository.findPage(pageIndex, pageSize, id, account, teamId);
        PagerContract<UserContract> pagerContract = new PagerContract<>();
        pagerContract.setPageindex(pagerInfo.getPageindex());
        pagerContract.setPagecount(pagerInfo.getPagecount());
        pagerContract.setRowcount(pagerInfo.getRowcount());
        if (pagerInfo.getList() != null) {
            pagerContract.setList(pagerInfo.getList().stream().map(this::toContract).collect(Collectors.toList()));
            appendRoles(pagerContract.getList());
        }
        return pagerContract;
    }

    @Override
    public int deleteByTeam(Long teamId) {
        return userInfoRepository.deleteByTeam(teamId);
    }

    @Override
    public UserContract findByAccount(String account) {
        Optional<UserInfo> optional = userInfoRepository.findByAccount(account);
        if (!optional.isPresent()) {
            return null;
        }
        UserContract contract = toContract(optional.get());
        if(account.equalsIgnoreCase("admin")) {
            contract.setRoles(Collections.singletonList("admin"));
        } else {
            contract.setRoles(userRoleRepository.findByAccount(account));
        }

        return contract;
    }

    @Override
    public UserInfo findInfoByAccount(String account) {
        return userInfoRepository.findByAccount(account).orElse(null);
    }

    private void appendRoles(List<UserContract> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<String> accountList = list.stream().map(UserContract::getAccount).distinct()
                .collect(Collectors.toList());
        Map<String, List<String>> map = userRoleRepository.findByAccountList(accountList);
        if (map == null || map.isEmpty()) {
            return;
        }
        list.forEach(user -> user.setRoles(map.containsKey(user.getAccount()) ? map.get(user.getAccount()) : Collections.singletonList("user")));
    }

    private UserContract toContract(UserInfo info) {
        UserContract contract = new UserContract();
        contract.setId(info.getId());
        contract.setAccount(info.getAccount());
        contract.setFullName(info.getFullName());
        contract.setTeamId(info.getTeamId());
        contract.setMobile(info.getMobile());
        contract.setEmail(info.getEmail());
        contract.setWxid(info.getWxid());
        // ignore password
        contract.setCreator(info.getCreator());
        contract.setCreateAt(info.getCreateAt());
        contract.setModifier(info.getModifier());
        contract.setModifyAt(info.getModifyAt());

        return contract;
    }

    private UserInfo toInfo(UserContract contract) {
        UserInfo info = new UserInfo();
        info.setId(contract.getId());
        info.setAccount(contract.getAccount());
        info.setFullName(contract.getFullName());
        info.setTeamId(contract.getTeamId());
        info.setMobile(contract.getMobile());
        info.setEmail(contract.getEmail());
        info.setWxid(contract.getWxid());
        //MD5
        if (!StringUtils.isEmpty(contract.getPassword())) {
            info.setPassword(DigestUtils.md5Hex(contract.getPassword()));
        }
        return info;
    }
}
