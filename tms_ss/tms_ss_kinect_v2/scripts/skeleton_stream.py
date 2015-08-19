#!/usr/bin/env python
# -*- coding:utf-8 -*-

from __future__ import print_function
import socket
from contextlib import closing

import os
import sys
import json

import numpy as np

# ROS dependency
import rospy
from geometry_msgs.msg import Vector3, Quaternion
from tms_ss_kinect_v2.msg import Skeleton
from tms_ss_kinect_v2.msg import CameraPosture
from tms_ss_kinect_v2.msg import SkeletonStreamWrapper

import OffsetManager
import NETWORK_SETTING


def q_mul(a, b):
    c = np.array([
        a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
        a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
        a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
        a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
    ])
    return c


def q_inv(a):
    b = np.array([
        -a[0],
        -a[1],
        -a[2],
        a[3]])
    return b


def q_toMat(a):
    b = np.empty((3, 3), float)
    b[0, 0] = a[3]*a[3]-a[0]*a[0]-a[1]*a[1]-a[2]*a[2]
    b[0, 1] = 2.0*(a[0]*a[1]-a[3]*a[2])
    b[0, 2] = 2.0*(a[0]*a[2]+a[3]*a[1])
    b[1, 0] = 2.0*(a[0]*a[1]+a[3]*a[2])
    b[1, 1] = a[3]*a[3]-a[0]*a[0]+a[1]*a[1]-a[2]*a[2]
    b[1, 2] = 2.0*(a[1]*a[2]-a[3]*a[0])
    b[2, 0] = 2.0*(a[0]*a[2]-a[3]*a[1])
    b[2, 1] = 2.0*(a[1]*a[2]+a[3]*a[0])
    b[2, 2] = a[3]*a[3]-a[0]*a[0]-a[1]*a[1]+a[2]*a[2]
    return b


class SkeletonStream:
    process_list = []
    sock = None

    def __init__(self, index):
        self.camera_id = index+1
        self.port = NETWORK_SETTING.PORT
        self.bufsize = 16383
        self.gotOffset = False

    def __getOffset(self):
        try:
            offset = OffsetManager.OffsetManager(self.camera_id-1)
            translation, rotation = offset.read()
            self.offsetT = np.array([
                translation[0],
                translation[1],
                translation[2]])
            self.offsetR = np.array([
                rotation[0],
                rotation[1],
                rotation[2],
                rotation[3]])
            self.gotOffset = True
            print('camera ' + str(self.camera_id) +
                  ': Read offset data successfully')
        except:
            self.offsetT = np.array([0.0, 0.0, 0.0])
            self.offsetR = np.array([0.0, 0.0, 0.0, 1.0])
            self.gotOffset = False
            print('camera ' + str(self.camera_id) +
                  ': Failed to read offset data')

    def __setFromJSONToSkeleton(self, json_str):
        # Joint names for kinect v2 SDK
        static_joint_name = (
            'SpineBase',
            'SpineMid',
            'Neck',
            'Head',
            'ShoulderLeft',
            'ElbowLeft',
            'WristLeft',
            'HandLeft',
            'ShoulderRight',
            'ElbowRight',
            'WristRight',
            'HandRight',
            'HipLeft',
            'KneeLeft',
            'AnkleLeft',
            'FootLeft',
            'HipRight',
            'KneeRight',
            'AnkleRight',
            'FootRight',
            'SpineShoulder',
            'HandTipLeft',
            'ThumbLeft',
            'HandTipRight',
            'ThumbRight'
        )
        obj = json.loads(json_str)
        self.data = Skeleton()
        self.data.user_id = obj['id']
        for joint_name in static_joint_name:
            self.data.name.append(joint_name)
        for joint in obj['joints']:
            self.data.confidence.append(joint['TrackingState'])
            self.data.position.append(
                Vector3(joint['CameraSpacePoint']['X'],
                        joint['CameraSpacePoint']['Y'],
                        joint['CameraSpacePoint']['Z']))
            self.data.orientation.append(
                Quaternion(0, 0, 0, 1))
        if obj['CameraParam'] is not None:
            print('Received camera parameters')
            self.camera = CameraPosture()
            translation = obj['CameraParam']['T']
            rotation = obj['CameraParam']['R']
            T = np.array([
                translation['X'],
                translation['Y'],
                translation['Z']])
            R = np.array([
                rotation['X'],
                rotation['Y'],
                rotation['Z'],
                rotation['W']])
            if self.gotOffset:
                T_old = T
                R_old = R
                print(self.offsetT)
                print(q_toMat(self.offsetR))
                T = np.dot(q_toMat(self.offsetR), T_old) + self.offsetT
                R = q_mul(self.offsetR, R_old)
            self.camera.translation.x = T[0]
            self.camera.translation.y = T[1]
            self.camera.translation.z = T[2]
            self.camera.rotation.x = R[0]
            self.camera.rotation.y = R[1]
            self.camera.rotation.z = R[2]
            self.camera.rotation.w = R[3]

    def run(self):
        # Getting offset
        self.__getOffset()
        # Getting skeleton and camera posture
        self.pub_skeleton = rospy.Publisher('skeleton_stream' +
                                            str(self.camera_id),
                                            Skeleton, queue_size=10)
        self.pub_camera = rospy.Publisher('camera_posture' +
                                          str(self.camera_id),
                                          CameraPosture, queue_size=10)
        self.pub_wrapper = rospy.Publisher('skeleton_stream_wrapper' +
                                           str(self.camera_id),
                                           SkeletonStreamWrapper,
                                           queue_size=10)
        rospy.init_node('skeleton_stream_bridge' + str(self.camera_id))
        self.freqency = rospy.Rate(10)

        while not rospy.is_shutdown():
            json_str, ip_addr = SkeletonStream.sock.recvfrom(self.bufsize)
            if ip_addr[0] == NETWORK_SETTING.IP_LIST[self.camera_id-1]:
                print('-----')
                self.__setFromJSONToSkeleton(json_str)
                rospy.loginfo('Sending skeleton {0}'
                              .format(self.data.user_id))
                data_wrapper = SkeletonStreamWrapper()
                data_wrapper.camera_number = self.camera_id
                data_wrapper.skeleton = self.data
                self.pub_skeleton.publish(self.data)
                if self.camera is not None:
                    data_wrapper.camera_posture = self.camera
                    self.pub_camera.publish(self.camera)
                self.pub_wrapper.publish(data_wrapper)
                self.freqency.sleep()


if __name__ == '__main__':
    try:
        argc = len(sys.argv)
        if (argc < 3):
            print('=== Usage ===\n[command] [host_ip] [camera_id_list ...]\n')
            print('--IP_LIST (refer NETWORK_SETTING.py)')
            for i in range(0, NETWORK_SETTING.length):
                print('  {0}: {1}'.format(i+1, NETWORK_SETTING.IP_LIST[i]))
            quit()
        else:
            localhost = sys.argv[1]
            pid_list = []
            SkeletonStream.process_list = [int(i)-1 for i in sys.argv[2:]]
            SkeletonStream.sock = socket.socket(socket.AF_INET,
                                                socket.SOCK_DGRAM)
            try:
                SkeletonStream.sock.bind((localhost,
                                          NETWORK_SETTING.PORT))
                for i in SkeletonStream.process_list:
                    pid_list.append(os.fork())
                    if pid_list[-1] == 0:
                        obj = SkeletonStream(i)
                        print('Receive from: {0}'
                              .format(NETWORK_SETTING.IP_LIST[i]))
                        print('Host IP: {0}'
                              .format(localhost))
                        print('Port: {0}'
                              .format(NETWORK_SETTING.PORT))
                        obj.run()
                os.wait()
            except:
                print('Failed to bind socket')
                closing(SkeletonStream.sock)

    except rospy.ROSInterruptException:
        pass
