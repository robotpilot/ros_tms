#include <ros/ros.h>
#include <nav_msgs/Odometry.h>
#include <geometry_msgs/Twist.h>
#include <geometry_msgs/Pose2D.h>
#include <geometry_msgs/PoseStamped.h>

#include "ClientSocket.h"
#include "SocketException.h"
#include <iostream>
#include <string>

#include <tf/tf.h>

#define ROS_RATE 10

using namespace std;

ClientSocket client_socket("", 54300);
const int ENC_MAX = 3932159;
const int SPEED_MAX = 32767;
const float DIST_PER_PULSE = 0.552486;  // mm par pulse
const int WHEEL_DIST = 570;             // 533;

// long int ENC_L = 0;
// long int ENC_R = 0;

pthread_t thread_odom;
pthread_t thread_pub;
pthread_mutex_t mutex_socket = PTHREAD_MUTEX_INITIALIZER;

class MachinePose_s {
 private:
 public:
  MachinePose_s() {
    ROS_DEBUG("In Mimamorukun Constructor");
    m_Odom.header.frame_id = "/odom";
    m_Odom.child_frame_id = "/base_link_footprint";
    m_Odom.pose.pose.position.z = 0.0;
    // this->pos_odom.x = 0.0;
    // this->pos_odom.y = 0.0;
    // this->pos_odom.theta = 0.0;
    // this->vel_odom.x = 0.0;
    // this->vel_odom.y = 0.0;
    // this->vel_odom.theta = 0.0;
  };
  ~MachinePose_s() {};
  void updateOdom();
  bool goPose(/*const geometry_msgs::Pose2D::ConstPtr& cmd_pose*/);
  bool postPose();
  nav_msgs::Odometry m_Odom;
  geometry_msgs::Twist tgtTwist;
  // geometry_msgs::Pose2D pos_odom;
  // geometry_msgs::PoseStamped pos_odom;
  // geometry_msgs::Twist    vel_odom;
  // geometry_msgs::Pose2D vel_odom;

  // void setCurrentPosition(geometry_msgs::Pose2D pose);
  void setCurrentPosition(geometry_msgs::Pose pose);
  geometry_msgs::Pose2D getCurrentPosition();
  // bool goPose2(/*const geometry_msgs::Pose2D::ConstPtr& cmd_pose*/);

} mchn_pose;

int Dist2Pulse(int dist) { return ((float)dist) / DIST_PER_PULSE; }
int Pulse2Dist(int pulse) { return ((float)pulse) * DIST_PER_PULSE; }
double Rad2Deg(double rad) { return rad * (180.0) / M_PI; }
double Deg2Rad(double deg) { return deg * M_PI / 180.0; }
// double Deg2Rad(double deg) { return deg * 3 / 180.0; }
double MM2M(double mm) { return mm * 0.001; }
double M2MM(double M) { return M * 1000; }
double sqr(double val) { return pow(val, 2); }
double Limit(double val, double max, double min) {
  if (val > max)
    return max;
  else if (min > val)
    return min;
  else
    return val;
}
double nomalizeAng(double rad) {
  while (rad > M_PI) {  //角度を-180°~180°(-π~π)の範囲に合わせる
    rad = rad - (2 * M_PI);
  }
  while (rad < -M_PI) {
    rad = rad + (2 * M_PI);
  }
  return rad;
}

void MachinePose_s::updateOdom() {
  // update Encoder value
  long int ENC_L, ENC_R;
  long int tmpENC_L = 0;
  long int tmpENC_R = 0;
  string reply;
  pthread_mutex_lock(&mutex_socket);
  client_socket << "@GP1@GP2"; /*use 250ms for send and get reply*/
  client_socket >> reply;
  pthread_mutex_unlock(&mutex_socket);
  ROS_DEBUG_STREAM("@GP raw:" << reply);
  sscanf(reply.c_str(), "@GP1,%ld@GP2,%ld", &tmpENC_L, &tmpENC_R);
  // ROS_DEBUG_STREAM("tmpENC_L:" << tmpENC_L << "    tmpENC_R:" << tmpENC_R);
  if (tmpENC_L > ENC_MAX / 2)
    ENC_L = tmpENC_L - (ENC_MAX + 1);
  else
    ENC_L = tmpENC_L;
  if (tmpENC_R > ENC_MAX / 2)
    ENC_R = tmpENC_R - (ENC_MAX + 1);
  else
    ENC_R = tmpENC_R;

  static long int ENC_R_old = 0;
  static long int ENC_L_old = 0;
  double detLp /*,r , dX ,dY,dL*/;

  if (fabs(ENC_L - ENC_L_old) > ENC_MAX / 2) ENC_L_old -= ENC_MAX + 1;
  if (fabs(ENC_R - ENC_R_old) > ENC_MAX / 2) ENC_R_old -= ENC_MAX + 1;

  //エンコーダーの位置での前回からの移動距離dL_R,dL_Lを算出
  double dL_L = (double)(ENC_L - ENC_L_old) * (-DIST_PER_PULSE);
  double dL_R = (double)(ENC_R - ENC_R_old) * DIST_PER_PULSE;

  //角速度SIGMAを算出
  double POS_SIGMA = (dL_R - dL_L) / WHEEL_DIST;
  double dL = (dL_R + dL_L) * 0.50000;

  // double dX = dL * cos(mchn_pose.pos_odom.theta + POS_SIGMA);  // X,Yの前回からの移動量計算
  // double dY = dL * sin(mchn_pose.pos_odom.theta + POS_SIGMA);
  double dX =
      dL * cos(tf::getYaw(m_Odom.pose.pose.orientation) + POS_SIGMA);  // X,Yの前回からの移動量計算
  double dY = dL * sin(tf::getYaw(m_Odom.pose.pose.orientation) + POS_SIGMA);
  ENC_R_old = ENC_R;  //前回のエンコーダーの値を記録
  ENC_L_old = ENC_L;
  // mchn_pose.pos_odom.x += dX;
  // mchn_pose.pos_odom.y += dY;
  // mchn_pose.pos_odom.theta += POS_SIGMA;
  // mchn_pose.pos_odom.theta = nomalizeAng(mchn_pose.pos_odom.theta);
  // mchn_pose.vel_odom.x = dX;
  // mchn_pose.vel_odom.y = dY;
  // mchn_pose.vel_odom.theta = POS_SIGMA;
  m_Odom.pose.pose.position.x += MM2M(dX);
  m_Odom.pose.pose.position.y += MM2M(dY);
  // m_Odom.pose.pose.orientation += POS_SIGMA;
  // m_Odom.pos_odom.theta = nomalizeAng(mchn_pose.pos_odom.theta);
  tf::Quaternion q1;
  tf::quaternionMsgToTF(m_Odom.pose.pose.orientation, q1);
  tf::Quaternion q2;
  tf::quaternionMsgToTF(tf::createQuaternionMsgFromYaw(POS_SIGMA), q2);
  q1 += q2;
  tf::quaternionTFToMsg(q1, m_Odom.pose.pose.orientation);
  m_Odom.twist.twist.linear.x = MM2M(dL) / (double)ROS_RATE;
  m_Odom.twist.twist.linear.y = 0.0;
  m_Odom.twist.twist.angular.z = POS_SIGMA;
  return;
}

/*------------------------------------
 * send command to mbed in wheel chair
 * argument:
 *   arg_speed: forward speed   [mm/sec]
 *   arg_theta: CCW turn speed  [radian/sec]
 * ----------------------------------*/
void spinWheel(/*double arg_speed, double arg_theta*/) {
  double arg_speed = M2MM(mchn_pose.tgtTwist.linear.x);
  double arg_theta = mchn_pose.tgtTwist.angular.z;
  // ROS_INFO("X:%4.2f   Theta:%4.2f",arg_speed,arg_theta);
  double val_L = -Dist2Pulse(arg_speed) + Dist2Pulse((WHEEL_DIST / 2) * arg_theta);
  double val_R = Dist2Pulse(arg_speed) + Dist2Pulse((WHEEL_DIST / 2) * arg_theta);
  val_L = (int)Limit(val_L, (double)SPEED_MAX, (double)-SPEED_MAX);
  val_R = (int)Limit(val_R, (double)SPEED_MAX, (double)-SPEED_MAX);
  ROS_DEBUG("val_L:%2.f   val_R:%2.f", val_L, val_R);

  string cmd_L = boost::lexical_cast<string>(val_L);
  string cmd_R = boost::lexical_cast<string>(val_R);

  string message;
  message = "@SS1," + cmd_L + "@SS2," + cmd_R;
  pthread_mutex_lock(&mutex_socket);
  client_socket << message;
  // message = "@SS2," + cmd_R;
  // client_socket << message;
  string reply;
  client_socket >> reply;
  pthread_mutex_unlock(&mutex_socket);
  ROS_DEBUG_STREAM("@SS raw: " << reply);
  // cout << "Response:" << reply << "   ";
}

void receiveCmdVel(const geometry_msgs::Twist::ConstPtr &cmd_vel) {
  // ROS_DEBUG("receive /cmd_vel");
  mchn_pose.tgtTwist = *cmd_vel;
  // spinWheel(/*cmd_vel->linear.x,cmd_vel->angular.z*/);
}

void *odom_update(void *ptr) {
  // ros::Rate r(30);
  ros::Rate r(ROS_RATE);
  while (ros::ok()) {
    mchn_pose.updateOdom();
    r.sleep();
  }
}

void *publish_odom(void *ptr) {
  static ros::NodeHandle nh("~");
  // static ros::Publisher pub = nh.advertise<geometry_msgs::PoseStamped>("kalman_pose", 1);
  static ros::Publisher pub = nh.advertise<nav_msgs::Odometry>("odom", 1);
  // nav_msgs::Odometry tmp_odom;
  // tmp_odom.header.frame_id = "/odom";
  // tmp_odom.child_frame_id = "/base_link_footprint";
  // tmp_odom.pose.pose.position.z = 0.0;
  ros::Rate r(ROS_RATE);
  while (ros::ok()) {
    // tmp_odom.pose.pose.position.x = mchn_pose.pos_odom.x;
    // tmp_odom.pose.pose.position.y = mchn_pose.pos_odom.y;
    // tmp_odom.header.stamp = ros::Time::now();
    // tmp_odom.pose.pose.orientation = tf::createQuaternionMsgFromYaw(mchn_pose.pos_odom.theta);
    // tmp_odom.twist.twist.linear.x = 0.0;
    // tmp_odom.twist.twist.linear.y = 0.0;
    // tmp_odom.twist.twist.angular.z = 0.0;
    // pub.publish(tmp_odom);
    pub.publish(mchn_pose.m_Odom);
    r.sleep();
  }
}

int main(int argc, char **argv) {
  ROS_INFO("wc_controller");
  ros::init(argc, argv, "wc_controller");
  ros::NodeHandle n;

  int Kp_, Ki_, Kd_;
  string s_Kp_, s_Ki_, s_Kd_;
  ros::NodeHandle nh_param("~");

  ros::Subscriber cmd_vel_sub = n.subscribe<geometry_msgs::Twist>("/cmd_vel", 10, receiveCmdVel);

  string tmp_ip;
  nh_param.param<string>("IP_ADDR", tmp_ip, "192.168.11.99");
  nh_param.param<int>("spin_Kp", Kp_, 4800);
  nh_param.param<int>("spin_Ki", Ki_, /*30*/ 100);
  nh_param.param<int>("spin_Kd", Kd_, 40000);
  // acces like "mimamorukun_controller/spd_Kp"
  client_socket.init(tmp_ip, 54300);
  s_Kp_ = boost::lexical_cast<string>(Kp_);
  s_Ki_ = boost::lexical_cast<string>(Ki_);
  s_Kd_ = boost::lexical_cast<string>(Kd_);

  try {
    string reply;
    try {
      // koyuusinndou 3Hz at Kp = 8000
      // client_socket << "@CR1@CR2@SM1,1@SM2,1@PP1,50@PP2,50@PI1,100@PI2,100@PD1,10@PD2,10";
      client_socket << "@CR1@CR2@SM1,1@SM2,1@PP1," + s_Kp_ + "@PP2," + s_Kp_ + "@PI1," + s_Ki_ +
                           "@PI2," + s_Ki_ + "@PD1," + s_Kd_ + "@PD2," + s_Kd_;
      client_socket >> reply;
    }
    catch (SocketException &) {
    }
    cout << "Response:" << reply << "\n";
    ;
  }
  catch (SocketException &e) {
    cout << "Exception was caught:" << e.description() << "\n";
  }

  // mchn_pose.updateVicon();
  // printf("initial val  x:%4.2lf y:%4.2lf th:%4.2lf\n\r", mchn_pose.pos_odom.x,
  // mchn_pose.pos_odom.y,
  //        Rad2Deg(mchn_pose.pos_odom.theta));
  printf("initial val  x:%4.2lf y:%4.2lf th:%4.2lf\n\r", mchn_pose.m_Odom.pose.pose.position.x,
         mchn_pose.m_Odom.pose.pose.position.y,
         Rad2Deg(tf::getYaw(mchn_pose.m_Odom.pose.pose.orientation)));
  // mchn_pose.setCurrentPosition(mchn_pose.pos_vicon);

  if (pthread_create(&thread_odom, NULL, odom_update, NULL)) {
    cout << "error creating thread." << endl;
    abort();
  }

  if (pthread_create(&thread_pub, NULL, publish_odom, NULL)) {
    cout << "error creating thread." << endl;
    abort();
  }

  ros::Rate r(ROS_RATE);
  while (n.ok()) {
    spinWheel();
    ros::spinOnce();
    r.sleep();
  }
  return (0);
}

/*****************************************************************************************************/

// void MachinePose_s::setCurrentPosition(geometry_msgs::Pose2D pose) { pos_odom = pose; }
void MachinePose_s::setCurrentPosition(geometry_msgs::Pose pose) {
  m_Odom.pose.pose = pose;
  // pos_odom = pose;
}
