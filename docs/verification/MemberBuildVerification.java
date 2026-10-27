import java.util.*;
import java.lang.reflect.*;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.flags.*;

class MemberBuildVerification {
    static LocalPlayer player(UUID id) {
        return (LocalPlayer) Proxy.newProxyInstance(LocalPlayer.class.getClassLoader(),new Class<?>[]{LocalPlayer.class},(proxy, method, args) -> {
            if(method.isDefault()) return InvocationHandler.invokeDefault(proxy,method,args);
            return switch(method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> id.toString();
                case "hasGroup","hasPermission","isPlayer" -> false;
                case "getGroups" -> new String[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
        });
    }
    public static void main(String[] args) {
        var owner=UUID.randomUUID(); var member=UUID.randomUUID(); var moderator=UUID.randomUUID(); var guest=UUID.randomUUID();
        var region=new GlobalProtectedRegion("__global__");
        region.getOwners().addPlayer(owner);
        region.getMembers().addPlayer(member);
        region.getMembers().addPlayer(moderator);
        region.setFlag(Flags.PASSTHROUGH,StateFlag.State.DENY);
        region.setFlag(Flags.BUILD,StateFlag.State.DENY);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(),RegionGroup.NON_MEMBERS);
        var result=new RegionResultSet(new ArrayList<>(),region);
        for(var entry:Map.of("owner",owner,"member",member,"moderator",moderator,"guest",guest).entrySet()) {
            System.out.println(entry.getKey()+" association="+player(entry.getValue()).getAssociation(List.of(region))); boolean actual=result.testState(player(entry.getValue()),Flags.BUILD);
            boolean expected=!entry.getKey().equals("guest");
            System.out.println(entry.getKey()+" BUILD="+actual+" expected="+expected);
            
        }
        region.setFlag(Flags.BUILD,null);
        for(var entry:Map.of("owner",owner,"member",member,"moderator",moderator,"guest",guest).entrySet()) {
            boolean actual=new RegionResultSet(new ArrayList<>(),region).testState(player(entry.getValue()),Flags.BUILD);
            System.out.println("CONTROL without explicit BUILD: "+entry.getKey()+" BUILD="+actual);
            if(actual==entry.getKey().equals("guest")) throw new AssertionError("control "+entry.getKey());
        }
        region.setFlag(Flags.BUILD,StateFlag.State.DENY);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(),RegionGroup.NON_OWNERS);
        for(var entry:Map.of("owner",owner,"member",member,"moderator",moderator,"guest",guest).entrySet()) {
            System.out.println("OWNER_ONLY: "+entry.getKey()+" BUILD="+new RegionResultSet(new ArrayList<>(),region).testState(player(entry.getValue()),Flags.BUILD));
        }
        region.setFlag(Flags.PASSTHROUGH,StateFlag.State.ALLOW);
        region.setFlag(Flags.BUILD,null);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(),null);
        for(var entry:Map.of("owner",owner,"member",member,"moderator",moderator,"guest",guest).entrySet()) {
            boolean actual=new RegionResultSet(new ArrayList<>(),region).testState(player(entry.getValue()),Flags.BUILD);
            System.out.println("PUBLIC: "+entry.getKey()+" BUILD="+actual);
            if(!actual) throw new AssertionError("public "+entry.getKey());
        }
        region.setFlag(Flags.PASSTHROUGH,StateFlag.State.DENY);
        region.getMembers().removePlayer(member);
        if(result.testState(player(member),Flags.BUILD)) throw new AssertionError("removed member");
        System.out.println("removed member BUILD=false");
    }
}





