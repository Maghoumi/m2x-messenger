/*
 * M2X Messenger, an implementation of the Yahoo Instant Messaging Client based on OpenYMSG for Android.
 * Copyright (C) 2011  Mehran Maghoumi [aka SirM2X], maghoumi@gmail.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sir_m2x.messenger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;

import org.openymsg.network.YahooProtocol;
import org.openymsg.network.YahooUser;
import org.openymsg.network.event.SessionAdapter;
import org.openymsg.network.event.SessionFriendAcceptedEvent;
import org.openymsg.network.event.SessionFriendEvent;
import org.openymsg.network.event.SessionNotifyEvent;

import android.content.ContextWrapper;
import android.content.Intent;
import android.util.Log;

import com.sir_m2x.messenger.services.MessengerService;

/**
 * An extension of SessionAdapter.
 * This class is primarily used to maintain an in-memory list of all of the current user's friends. <br>
 * Currently Yahoo! sends the list of our friends only once (at the logon time) and for group or friend-specific operations
 * maintaining a separate list is crucial.
 * This class also handles various list operations (ex: Add/Remove friends)
 * 
 * @author Mehran Maghoumi [aka SirM2X] (maghoumi@gmail.com)
 *
 */
public class FriendsList extends SessionAdapter
{

	private static TreeMap<String, ArrayList<YahooUser>> masterList = null;
	private static HashMap<String, YahooUser> allFriends = null;

	public static void fillFriends(TreeMap<String, ArrayList<YahooUser>> friendsList)
	{
		FriendsList.masterList = friendsList;
		FriendsList.allFriends = new HashMap<String, YahooUser>();

		for (String groupName : FriendsList.masterList.keySet())
		{
			for (YahooUser u : FriendsList.masterList.get(groupName))
			{
				FriendsList.allFriends.put(u.getId(), u);
			}
		}
	}

	public static void moveFriend(String friendId, String fromGroup, String toGroup) throws IOException
	{
		MessengerService.getSession().moveFriend(friendId, fromGroup, toGroup);
		YahooUser user = allFriends.get(friendId);
		FriendsList.masterList.get(fromGroup).remove(user); // The group must now be indexed under a different group
		user.changeGroup(fromGroup, toGroup);
		FriendsList.masterList.get(toGroup).add(user);
		SortMasterList();
	}

	public static void removeFriendFromGroup(String friendId, String groupId) throws IOException
	{
		MessengerService.getSession().removeFriendFromGroup(friendId, groupId);
		YahooUser user = allFriends.get(friendId);
		user.removeGroupId(groupId);
		masterList.get(groupId).remove(user);
		if (user.getGroupIds().size() == 0) //effectively remove this friend from our friends list
			allFriends.remove(friendId);
	}

	public static void deleteGroup(String groupId) throws IOException
	{
		ArrayList<YahooUser> usersInGroup = masterList.get(groupId);

		for (YahooUser user : usersInGroup)
		{
			//MessengerService.getSession().removeFriendFromGroup(friendId, groupId);
			user.removeGroupId(groupId);
		}

		for (YahooUser user : usersInGroup)
		{
			if (user.getGroupIds().size() == 0)
				allFriends.remove(user.getId());
		}

		masterList.remove(groupId); //we just have to remove all the users from this group and 
									//remove the group from our local list 
									//as yahoo does not provide a mechanism for deleting a group
	}

	public static void renameGroup(String oldName, String newName) throws IllegalStateException, IOException
	{
		if (oldName.equals(newName))
			return;

		MessengerService.getSession().renameGroup(oldName, newName);
		ArrayList<YahooUser> usersOnGroup = masterList.get(oldName);

		for (YahooUser user : masterList.get(oldName))
		{
			user.removeGroupId(oldName);
			user.addGroupId(newName);
		}

		masterList.remove(oldName);
		masterList.put(newName, usersOnGroup);
	}

	public static void addFriend(String userId, String groupId) throws IOException
	{
		YahooUser newUser = null;
		if (allFriends.containsKey(userId))
			if (!allFriends.get(userId).isPending())
			{
				return;
			}
			else
				newUser = allFriends.get(userId);

		MessengerService.getSession().sendNewFriendRequest(userId, groupId, YahooProtocol.YAHOO);

		if(newUser == null)
		{
			newUser = new YahooUser(userId, groupId, YahooProtocol.YAHOO);
			newUser.setPending(true);
			allFriends.put(userId, newUser);
			masterList.get(groupId).add(newUser);
			SortMasterList();
		}	
	}

	public static TreeMap<String, ArrayList<YahooUser>> getMasterList()
	{
		return FriendsList.masterList;
	}

	@Override
	public void friendSignedOn(SessionFriendEvent event)
	{
		if (allFriends.size() == 0)
			return;
		updateFriendByEvent(event);
	}

	@Override
	public void friendSignedOff(SessionFriendEvent event)
	{
		if (allFriends.size() == 0)
			return;
		updateFriendByEvent(event);
	}

	@Override
	public void friendsUpdateReceived(SessionFriendEvent event)
	{
		if (allFriends.size() == 0)
			return;
		updateFriendByEvent(event);
	}

	@Override
	public void notifyReceived(SessionNotifyEvent event)
	{
		if (allFriends.size() == 0)
			return;

		String userId = event.getFrom();
		if (event.isTyping())
		{
			FriendsList.allFriends.get(userId).setIsTyping(event.isOn());
		}
	}

	private void updateFriendByEvent(SessionFriendEvent event)
	{
		try
		{
			YahooUser user = event.getUser();
			String userId = user.getId();

			YahooUser existingUser = FriendsList.allFriends.get(userId);
			existingUser.update(user.getStatus(), user.isOnChat(), user.isOnPager());
			existingUser.setCustom(user.getCustomStatusMessage(), user.getCustomStatus());
		}
		catch (Exception ex)
		{
			Log.w("updateFriendByEvent", ex);
			Log.w("updateFriendByEvent", event.toString());
		}
	}
	
	@Override
	public void contactAcceptedReceived(SessionFriendAcceptedEvent event)
	{
		YahooUser user = event.getUser();
		if (allFriends.containsKey(user.getId()))
		{
			allFriends.get(user.getId()).setPending(false);
		}
		//add the user to the friends list
		//TODO the user must confirm to add if the user does not exist on the pending list
		ContextWrapper wrapper = new ContextWrapper(MySessionAdapter.context);
		Intent intent = new Intent();
		intent.setAction(MessengerService.INTENT_LIST_CHANGED);
		//TODO build a notification based on this
		wrapper.sendBroadcast(intent);
	}
	
	public static void SortMasterList()
	{
		for(String groupName : masterList.keySet())
		{
			Collections.sort(masterList.get(groupName), YahooUser.getComparator());
		}
	}

}
